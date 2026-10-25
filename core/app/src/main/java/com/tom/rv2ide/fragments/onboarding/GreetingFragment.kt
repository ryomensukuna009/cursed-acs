/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.fragments.onboarding

import android.os.Bundle
import android.view.View
import com.tom.rv2ide.databinding.FragmentOnboardingGreetingBinding
import com.tom.rv2ide.fragments.FragmentWithBinding
import com.tom.rv2ide.utils.AppLogoManager

/** @author Akash Yadav */
class GreetingFragment :
    FragmentWithBinding<FragmentOnboardingGreetingBinding>(
        FragmentOnboardingGreetingBinding::inflate
    ) {
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    AppLogoManager.bindLogo(binding.icon)
  }
}
