package com.tom.rv2ide.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.fragments.ChatFragment
import com.tom.rv2ide.fragments.AIHistoryFragment

class ViewPagerAdapter : FragmentStateAdapter {

    private val aiAgent: AIAgentManager

    constructor(fragment: Fragment, aiAgent: AIAgentManager) : super(fragment) {
        this.aiAgent = aiAgent
    }

    constructor(fragmentActivity: FragmentActivity, aiAgent: AIAgentManager) : super(fragmentActivity) {
        this.aiAgent = aiAgent
    }

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ChatFragment.newInstance(aiAgent)
            1 -> AIHistoryFragment.newInstance(aiAgent)
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}