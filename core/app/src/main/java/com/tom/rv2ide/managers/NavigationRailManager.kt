package com.tom.rv2ide.managers

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import androidx.core.view.isVisible
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigationrail.NavigationRailView

class NavigationRailManager(
    private val navigationRail: NavigationRailView,
    private val overlayView: View,
    private val fabToggle: FloatingActionButton
) {
    
    private var isExpanded = false
    private var currentAnimator: ValueAnimator? = null
    private var railWidth: Float = 0f
    
    init {
        setupToggleButton()
        setupOverlayClick()
        measureRailWidth()
    }
    
    private fun measureRailWidth() {
        navigationRail.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                railWidth = navigationRail.width.toFloat()
                if (railWidth > 0) {
                    navigationRail.translationX = -railWidth
                    navigationRail.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
    }
    
    private fun setupToggleButton() {
        fabToggle.setOnClickListener {
            toggle()
        }
    }
    
    private fun setupOverlayClick() {
        overlayView.setOnClickListener {
            if (isExpanded) {
                collapse()
            }
        }
    }
    
    fun toggle() {
        if (isExpanded) {
            collapse()
        } else {
            expand()
        }
    }
    
    fun expand() {
        if (isExpanded || currentAnimator?.isRunning == true) return
        
        isExpanded = true
        
        if (railWidth == 0f) {
            railWidth = navigationRail.width.toFloat()
        }
        
        navigationRail.isVisible = true
        
        navigationRail.animate()
            .translationX(0f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()
        
        overlayView.isVisible = true
        overlayView.animate()
            .alpha(0.6f)
            .setDuration(300)
            .start()
        
        fabToggle.animate()
            .rotation(90f)
            .setDuration(300)
            .start()
    }
    
    fun collapse() {
        if (!isExpanded || currentAnimator?.isRunning == true) return
        
        isExpanded = false
        
        if (railWidth == 0f) {
            railWidth = navigationRail.width.toFloat()
        }
        
        navigationRail.animate()
            .translationX(-railWidth)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                navigationRail.isVisible = false
            }
            .start()
        
        overlayView.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                overlayView.isVisible = false
            }
            .start()
        
        fabToggle.animate()
            .rotation(0f)
            .setDuration(300)
            .start()
    }
    
    fun isRailExpanded(): Boolean = isExpanded
    
    fun cleanup() {
        currentAnimator?.cancel()
        currentAnimator = null
    }
}