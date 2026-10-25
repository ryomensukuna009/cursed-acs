/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.builder.offline

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Packages and aligns APK files according to Android ZIP alignment requirements.
 *
 * - Shared libraries in lib/ directory are uncompressed (STORED) and aligned to 16,384 bytes (16KB)
 *   to comply with Android 15 page alignment rules.
 * - resources.arsc and other uncompressed entries are aligned to 4-byte boundaries.
 * - DEX files and resource drawables/layouts are DEFLATED.
 */
class ApkZipAligner {

  companion object {
    const val ALIGNMENT_4_BYTES = 4
    const val ALIGNMENT_16KB_PAGE = 16384

    private const val LFH_MAGIC = 0x04034b50
    private const val CDH_MAGIC = 0x02014b50
    private const val EOCD_MAGIC = 0x06054b50
  }

  private data class WrittenEntry(
      val name: String,
      val nameBytes: ByteArray,
      val extraBytes: ByteArray,
      val compressionMethod: Int,
      val time: Int,
      val date: Int,
      val crc: Long,
      val compressedSize: Long,
      val uncompressedSize: Long,
      val lfhOffset: Long
  )

  fun packageAndAlign(
      resourcesApFile: File,
      dexFiles: List<File>,
      nativeLibraries: List<File>,
      outputApkFile: File,
      listener: OfflineBuildProgressListener? = null
  ): OfflineStageResult {
    val startTime = System.currentTimeMillis()

    if (!resourcesApFile.exists() || resourcesApFile.length() == 0L) {
      return OfflineStageResult(
          stage = OfflineBuildStage.PACKAGE,
          success = false,
          message = "Base resources archive not found: ${resourcesApFile.absolutePath}",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    if (dexFiles.isEmpty() && nativeLibraries.isEmpty()) {
      return OfflineStageResult(
          stage = OfflineBuildStage.PACKAGE,
          success = false,
          message = "No DEX files or native libraries provided to package into APK.",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    listener?.onStageLog(
        OfflineBuildStage.PACKAGE,
        "Assembling APK with ${dexFiles.size} DEX file(s) and ${nativeLibraries.size} native library(s)..."
    )

    try {
      if (outputApkFile.exists()) {
        outputApkFile.delete()
      }
      outputApkFile.parentFile?.mkdirs()

      var totalWrittenEntries = 0

      FileOutputStream(outputApkFile).use { fos ->
        BufferedOutputStream(fos, 65536).use { out ->
          val writtenEntries = mutableListOf<WrittenEntry>()
          var currentOffset = 0L

          // 1. Copy entries from resources.ap_
          ZipFile(resourcesApFile).use { resZip ->
            val entries = resZip.entries()
            while (entries.hasMoreElements()) {
              val zipEntry = entries.nextElement()
              if (zipEntry.isDirectory) continue

              val name = zipEntry.name
              val entryData = resZip.getInputStream(zipEntry).use { it.readBytes() }

              val isStored = zipEntry.method == ZipEntry.STORED || name == "resources.arsc"
              val alignment = if (isStored) ALIGNMENT_4_BYTES else 1

              val written = writeEntry(
                  out = out,
                  name = name,
                  data = entryData,
                  forceStored = isStored,
                  alignment = alignment,
                  currentOffset = currentOffset
              )
              writtenEntries.add(written.entry)
              currentOffset = written.newOffset
            }
          }

          // 2. Add classes.dex and secondary DEX files
          for (dexFile in dexFiles) {
            val name = dexFile.name
            val dexData = dexFile.readBytes()

            // DEX files are deflated to reduce APK download and disk footprint
            val written = writeEntry(
                out = out,
                name = name,
                data = dexData,
                forceStored = false,
                alignment = 1,
                currentOffset = currentOffset
            )
            writtenEntries.add(written.entry)
            currentOffset = written.newOffset
            listener?.onStageLog(OfflineBuildStage.PACKAGE, "Added $name (${dexData.size} bytes)")
          }

          // 3. Add native libraries under lib/arm64-v8a/
          for (lib in nativeLibraries) {
            val name = "lib/arm64-v8a/" + lib.name
            val libData = lib.readBytes()

            // Shared libraries MUST be STORED and 16KB aligned for Android 15+ direct memory mapping
            val written = writeEntry(
                out = out,
                name = name,
                data = libData,
                forceStored = true,
                alignment = ALIGNMENT_16KB_PAGE,
                currentOffset = currentOffset
            )
            writtenEntries.add(written.entry)
            currentOffset = written.newOffset
            listener?.onStageLog(
                OfflineBuildStage.PACKAGE,
                "Added $name (${libData.size} bytes, STORED, 16KB page-aligned)"
            )
          }

          // 4. Write Central Directory
          val cdOffset = currentOffset
          for (entry in writtenEntries) {
            val cdh = ByteBuffer.allocate(46 + entry.nameBytes.size + entry.extraBytes.size)
                .order(ByteOrder.LITTLE_ENDIAN)

            cdh.putInt(CDH_MAGIC)
            cdh.putShort(20.toShort()) // version made by
            cdh.putShort(20.toShort()) // version needed
            cdh.putShort(0x0800.toShort()) // general purpose bit flag (UTF-8)
            cdh.putShort(entry.compressionMethod.toShort())
            cdh.putShort(entry.time.toShort())
            cdh.putShort(entry.date.toShort())
            cdh.putInt(entry.crc.toInt())
            cdh.putInt(entry.compressedSize.toInt())
            cdh.putInt(entry.uncompressedSize.toInt())
            cdh.putShort(entry.nameBytes.size.toShort())
            cdh.putShort(entry.extraBytes.size.toShort())
            cdh.putShort(0.toShort()) // comment length
            cdh.putShort(0.toShort()) // disk number start
            cdh.putShort(0.toShort()) // internal attributes
            cdh.putInt(0) // external attributes
            cdh.putInt(entry.lfhOffset.toInt())
            cdh.put(entry.nameBytes)
            cdh.put(entry.extraBytes)

            val cdhBytes = cdh.array()
            out.write(cdhBytes)
            currentOffset += cdhBytes.size
          }
          val cdSize = currentOffset - cdOffset

          // 5. Write End of Central Directory (EOCD)
          val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
          eocd.putInt(EOCD_MAGIC)
          eocd.putShort(0.toShort()) // disk number
          eocd.putShort(0.toShort()) // start disk
          eocd.putShort(writtenEntries.size.toShort())
          eocd.putShort(writtenEntries.size.toShort())
          eocd.putInt(cdSize.toInt())
          eocd.putInt(cdOffset.toInt())
          eocd.putShort(0.toShort()) // comment length

          out.write(eocd.array())
          out.flush()
          totalWrittenEntries = writtenEntries.size
        }
      }

      val apkSize = outputApkFile.length()
      listener?.onStageLog(
          OfflineBuildStage.PACKAGE,
          "APK assembled and aligned successfully: ${outputApkFile.name} ($apkSize bytes, $totalWrittenEntries entries)."
      )

      return OfflineStageResult(
          stage = OfflineBuildStage.PACKAGE,
          success = true,
          message = "Assembled and aligned APK ($totalWrittenEntries entries, $apkSize bytes).",
          durationMs = System.currentTimeMillis() - startTime
      )
    } catch (e: Exception) {
      return OfflineStageResult(
          stage = OfflineBuildStage.PACKAGE,
          success = false,
          message = "APK packaging and alignment failed: ${e.message}",
          durationMs = System.currentTimeMillis() - startTime,
          details = e.stackTraceToString()
      )
    }
  }

  private data class WriteResult(val entry: WrittenEntry, val newOffset: Long)

  private fun writeEntry(
      out: BufferedOutputStream,
      name: String,
      data: ByteArray,
      forceStored: Boolean,
      alignment: Int,
      currentOffset: Long
  ): WriteResult {
    val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
    val crc = CRC32().apply { update(data) }.value

    val (compressionMethod, dataToWrite) = if (forceStored) {
      Pair(ZipEntry.STORED, data)
    } else {
      val deflated = deflate(data)
      // Only use deflated if it's smaller than uncompressed
      if (deflated.size < data.size) {
        Pair(ZipEntry.DEFLATED, deflated)
      } else {
        Pair(ZipEntry.STORED, data)
      }
    }

    val uncompressedSize = data.size.toLong()
    val compressedSize = dataToWrite.size.toLong()

    // Calculate padding for LFH extra field to ensure data starts on alignment boundary
    var extraBytes = ByteArray(0)
    if (compressionMethod == ZipEntry.STORED && alignment > 1) {
      val headerSizeWithoutPadding = 30 + nameBytes.size
      val dataOffsetWithoutPadding = currentOffset + headerSizeWithoutPadding
      val remainder = (dataOffsetWithoutPadding % alignment).toInt()
      if (remainder != 0) {
        val paddingNeeded = alignment - remainder
        extraBytes = ByteArray(paddingNeeded)
      }
    }

    val lfh = ByteBuffer.allocate(30 + nameBytes.size + extraBytes.size).order(ByteOrder.LITTLE_ENDIAN)
    lfh.putInt(LFH_MAGIC)
    lfh.putShort(20.toShort()) // version needed
    lfh.putShort(0x0800.toShort()) // UTF-8
    lfh.putShort(compressionMethod.toShort())
    lfh.putShort(0.toShort()) // time
    lfh.putShort(0x21.toShort()) // date
    lfh.putInt(crc.toInt())
    lfh.putInt(compressedSize.toInt())
    lfh.putInt(uncompressedSize.toInt())
    lfh.putShort(nameBytes.size.toShort())
    lfh.putShort(extraBytes.size.toShort())
    lfh.put(nameBytes)
    if (extraBytes.isNotEmpty()) {
      lfh.put(extraBytes)
    }

    val lfhBytes = lfh.array()
    out.write(lfhBytes)
    out.write(dataToWrite)

    val entryOffset = currentOffset
    val nextOffset = currentOffset + lfhBytes.size + dataToWrite.size

    val entry = WrittenEntry(
        name = name,
        nameBytes = nameBytes,
        extraBytes = extraBytes,
        compressionMethod = compressionMethod,
        time = 0,
        date = 0x21,
        crc = crc,
        compressedSize = compressedSize,
        uncompressedSize = uncompressedSize,
        lfhOffset = entryOffset
    )

    return WriteResult(entry, nextOffset)
  }

  private fun deflate(input: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    deflater.setInput(input)
    deflater.finish()

    val bos = ByteArrayOutputStream(input.size)
    val buffer = ByteArray(8192)
    while (!deflater.finished()) {
      val count = deflater.deflate(buffer)
      bos.write(buffer, 0, count)
    }
    deflater.end()
    return bos.toByteArray()
  }
}
