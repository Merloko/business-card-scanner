package com.businesscard.scanner.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.businesscard.scanner.R
import com.businesscard.scanner.databinding.ActivityImageViewerBinding
import java.io.File

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val paths = intent.getStringArrayListExtra(EXTRA_PATHS)?.takeIf { it.isNotEmpty() } ?: return finish()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, paths.size - 1)

        val adapter = ImagePagerAdapter(paths)
        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(startIndex, false)

        if (paths.size <= 1) binding.pageIndicator.visibility = View.GONE
        updateIndicator(startIndex, paths.size)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position, paths.size)
                binding.labelPage.text = getString(if (position == 0) R.string.image_front_label else R.string.image_back_label)
            }
        })

        binding.labelPage.text = getString(if (startIndex == 0) R.string.image_front_label else R.string.image_back_label)
        binding.root.setOnClickListener { finish() }
    }

    private fun updateIndicator(position: Int, count: Int) {
        binding.dot0.background = ContextCompat.getDrawable(this,
            if (position == 0) R.drawable.indicator_dot_active else R.drawable.indicator_dot
        )
        if (count > 1) {
            binding.dot1.background = ContextCompat.getDrawable(this,
                if (position == 1) R.drawable.indicator_dot_active else R.drawable.indicator_dot
            )
        }
    }

    companion object {
        const val EXTRA_PATHS = "extra_paths"
        const val EXTRA_START_INDEX = "extra_start_index"
    }
}

private class ImagePagerAdapter(private val paths: List<String>) :
    RecyclerView.Adapter<ImagePagerAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(com.businesscard.scanner.R.id.pageImage)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(com.businesscard.scanner.R.layout.item_image_page, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        Glide.with(holder.image)
            .load(File(paths[position]))
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .into(holder.image)
    }

    override fun getItemCount() = paths.size
}
