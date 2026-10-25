package com.tom.rv2ide.fragments.sidebar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.ViewPagerAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.fragments.ChatFragment
import com.tom.rv2ide.ui.CodeEditorView

class ArtificialFragment(
    private val editorView: CodeEditorView? = null
) : Fragment() {

    private lateinit var aiAgent: AIAgentManager
    private lateinit var agents: Agents
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var undoFab: ExtendedFloatingActionButton
    private lateinit var btnAgentSettings: MaterialButton
    private lateinit var contentContainer: View
    
    private var savedViewPagerPosition = 0
    private var savedContentContainerVisibility = View.GONE
    
    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showMainContent()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            savedViewPagerPosition = it.getInt(KEY_VIEWPAGER_POSITION, 0)
            savedContentContainerVisibility = it.getInt(KEY_CONTENT_VISIBILITY, View.GONE)
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_artificial, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    
        aiAgent = AIAgentManager(requireContext())
        agents = Agents(requireContext())
    
        viewPager = view.findViewById(R.id.viewPager)
        tabLayout = view.findViewById(R.id.tabLayout)
        undoFab = view.findViewById(R.id.undoFab)
        btnAgentSettings = view.findViewById(R.id.btnAgentSettings)
        contentContainer = view.findViewById(R.id.contentContainer)
    
        btnAgentSettings.setOnClickListener {
            openAIPreferences()
        }
    
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
    
        setupFab()
        
        view.post {
            setupViewPager()
            
            if (savedInstanceState != null) {
                val hasContainerFragment = childFragmentManager.findFragmentById(R.id.contentContainer) != null
                val effectiveVisibility = if (hasContainerFragment) savedContentContainerVisibility else View.GONE
                viewPager.setCurrentItem(savedViewPagerPosition, false)
                contentContainer.visibility = effectiveVisibility
                viewPager.visibility = if (effectiveVisibility == View.VISIBLE) View.GONE else View.VISIBLE
                tabLayout.visibility = if (effectiveVisibility == View.VISIBLE) View.GONE else View.VISIBLE
                backPressedCallback.isEnabled = effectiveVisibility == View.VISIBLE
            }
        }
    }
    
    fun selectTab(position: Int) {
        if (::viewPager.isInitialized) {
            viewPager.currentItem = position
        }
    }

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this, aiAgent)
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 1
        viewPager.isUserInputEnabled = true
    
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                0 -> {
                    tab.text = "Chat"
                    tab.setIcon(R.drawable.ic_chat)
                }
                1 -> {
                    tab.text = "History"
                    tab.setIcon(R.drawable.ic_history)
                }
                else -> {
                    tab.text = ""
                }
            }
        }.attach()
        
        viewPager.post {
            viewPager.requestLayout()
        }
    }
        
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::viewPager.isInitialized) {
            outState.putInt(KEY_VIEWPAGER_POSITION, viewPager.currentItem)
        }
        if (::contentContainer.isInitialized) {
            outState.putInt(KEY_CONTENT_VISIBILITY, contentContainer.visibility)
        }
    }
    
    private fun getCurrentChatFragment(): ChatFragment? {
        val fragments = childFragmentManager.fragments
        return fragments.find { it is ChatFragment && it.isVisible } as? ChatFragment
    }

    fun openAIPreferences() {
        val chatFragment = getCurrentChatFragment()
        val completionManager = chatFragment?.getCodeCompletionManager()
        
        val preferencesFragment = AIPreferencesFragment(
            aiAgent,
            agents,
            completionManager
        )
        
        val slideIn = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_in_left)
        val slideOut = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_out_right)
        
        viewPager.startAnimation(slideOut)
        viewPager.visibility = View.GONE
        tabLayout.visibility = View.GONE
        btnAgentSettings.visibility = View.GONE
        
        contentContainer.visibility = View.VISIBLE
        contentContainer.startAnimation(slideIn)
        backPressedCallback.isEnabled = true
        
        childFragmentManager.commit {
            replace(R.id.contentContainer, preferencesFragment)
            addToBackStack("ai_preferences")
        }
    }

    fun showMainContent() {
        val slideIn = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_in_left)
        val slideOut = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_out_right)
        
        contentContainer.startAnimation(slideOut)
        contentContainer.visibility = View.GONE
        
        viewPager.visibility = View.VISIBLE
        tabLayout.visibility = View.VISIBLE
        btnAgentSettings.visibility = View.VISIBLE
        viewPager.startAnimation(slideIn)
        
        backPressedCallback.isEnabled = false
        
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
        }
    }

    private fun setupFab() {
        undoFab.setOnClickListener {
            val success = aiAgent.undoLastModification()
            if (success) {
                view?.let {
                    Snackbar.make(it, "Last modification undone", Snackbar.LENGTH_SHORT).show()
                }
                undoFab.visibility = View.GONE
            } else {
                view?.let {
                    Snackbar.make(it, "Nothing to undo", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    override fun onDestroyView() {
        backPressedCallback.remove()
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        com.tom.rv2ide.utils.EditorSidebarActions.removeFragmentFromCache("ide.editor.sidebar.ai_agent")
    }
    
    companion object {
        private const val KEY_VIEWPAGER_POSITION = "viewpager_position"
        private const val KEY_CONTENT_VISIBILITY = "content_visibility"
    }
}