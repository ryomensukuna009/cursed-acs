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

package com.tom.rv2ide.artificial.exceptions

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

class RateLimitException(message: String, val retryAfterSeconds: Long? = null) : Exception(message)
class QuotaExceededException(message: String) : Exception(message)
class InsufficientBalanceException(message: String) : Exception(message)
class InvalidApiKeyException(message: String) : Exception(message)
class NetworkErrorException(message: String, cause: Throwable? = null) : Exception(message, cause)
class UnsupportedEndpointException(message: String, val httpCode: Int = 404) : Exception(message)
class MalformedApiResponseException(message: String, cause: Throwable? = null) : Exception(message, cause)
class InsecureEndpointException(message: String) : Exception(message)
class UnsafeUrlException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ToolExecutionLimitException(message: String) : Exception(message)
class ProviderRequestException(
    message: String,
    val httpCode: Int = -1,
    val errorCode: String? = null,
    val errorType: String? = null
) : Exception(message) {
    constructor(statusCode: Int, message: String) : this(message, statusCode, null, null)
    constructor(statusCode: Int, message: String, errorCode: String?, errorType: String? = null) :
        this(message, statusCode, errorCode, errorType)
    val statusCode: Int
        get() = httpCode
}
