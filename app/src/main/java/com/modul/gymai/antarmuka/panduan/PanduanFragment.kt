package com.modul.gymai.antarmuka.panduan

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.modul.gymai.R
import com.modul.gymai.antarmuka.latihan.Exercise
import com.modul.gymai.antarmuka.latihan.ExerciseDetailRepository
import com.modul.gymai.antarmuka.latihan.ExerciseRepository
import com.modul.gymai.antarmuka.latihan.TutorialMediaType
import com.modul.gymai.databinding.FragmentPanduanBinding
import com.modul.gymai.ui.MaterialSymbols

class PanduanFragment : Fragment() {

    private var _binding: FragmentPanduanBinding? = null
    private val binding get() = _binding!!
    private var tutorialPlayer: ExoPlayer? = null
    private var tutorialGif: AnimatedImageDrawable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPanduanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        MaterialSymbols.applyToTree(binding.root)

        val exercises = ExerciseRepository.getAllExercises()

        val cardMap = mapOf(
            binding.cardSquat to exercises.find { it.id == "1" },
            binding.cardBicepCurl to exercises.find { it.id == "2" },
            binding.cardLateralRaise to exercises.find { it.id == "3" },
            binding.cardShoulderPress to exercises.find { it.id == "4" }
        )

        cardMap.forEach { (card, exercise) ->
            exercise?.let {
                card.setOnClickListener {
                    showExerciseModal(exercise)
                }
            }
        }
    }

    private fun showExerciseModal(exercise: Exercise) {
        val detail = ExerciseDetailRepository.getDetail(exercise.id) ?: return

        val dialog = BottomSheetDialog(requireContext(), R.style.BottomSheetDialogTheme)
        val sheetView = LayoutInflater.from(requireContext())
            .inflate(R.layout.bottom_sheet_panduan, null)
        MaterialSymbols.applyToTree(sheetView)

        dialog.setContentView(sheetView)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                val displayMetrics = resources.displayMetrics
                it.layoutParams.height = (displayMetrics.heightPixels * 0.90).toInt()
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        sheetView.findViewById<ImageView>(R.id.iv_modal_image).setImageResource(exercise.imageResId)
        sheetView.findViewById<TextView>(R.id.tv_modal_name).text = exercise.name
        sheetView.findViewById<TextView>(R.id.tv_modal_muscle).text = exercise.primaryMuscleGroup
        sheetView.findViewById<TextView>(R.id.tv_modal_level).text = exercise.level
        sheetView.findViewById<TextView>(R.id.tv_modal_steps_count).text = detail.steps.size.toString()
        sheetView.findViewById<TextView>(R.id.tv_modal_equipment).text = exercise.equipment
        sheetView.findViewById<TextView>(R.id.tv_modal_definition).text = detail.definition
        configureTutorialMedia(sheetView, exercise, detail.tutorialMediaName, detail.tutorialMediaType)

        val llSteps = sheetView.findViewById<LinearLayout>(R.id.ll_modal_steps)
        detail.steps.forEachIndexed { index, step ->
            llSteps.addView(createStepItem(index + 1, step))
        }

        val llCorrect = sheetView.findViewById<LinearLayout>(R.id.ll_modal_correct)
        detail.correctTechniques.forEach { tech ->
            llCorrect.addView(createBulletItem(tech, isCorrect = true))
        }

        val llWrong = sheetView.findViewById<LinearLayout>(R.id.ll_modal_wrong)
        detail.wrongTechniques.forEach { tech ->
            llWrong.addView(createBulletItem(tech, isCorrect = false))
        }

        val llTips = sheetView.findViewById<LinearLayout>(R.id.ll_modal_tips)
        detail.tips.forEach { tip ->
            llTips.addView(createTipItem(tip))
        }

        sheetView.findViewById<ImageView>(R.id.btn_modal_close).setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            releaseTutorialMedia()
        }

        dialog.show()
    }

    private fun configureTutorialMedia(
        sheetView: View,
        exercise: Exercise,
        mediaName: String,
        mediaType: TutorialMediaType
    ) {
        releaseTutorialMedia()

        val playerView = sheetView.findViewById<PlayerView>(R.id.player_modal_tutorial)
        val placeholder = sheetView.findViewById<ImageView>(R.id.iv_modal_video_placeholder)
        val dimView = sheetView.findViewById<View>(R.id.view_modal_video_dim)
        val playButton = sheetView.findViewById<ImageButton>(R.id.btn_modal_video_play)
        val statusText = sheetView.findViewById<TextView>(R.id.tv_modal_video_status)

        placeholder.setImageResource(exercise.imageResId)
        playerView.visibility = View.GONE
        placeholder.visibility = View.VISIBLE
        dimView.visibility = View.VISIBLE
        playButton.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = "Video tutorial belum tersedia"

        val mediaResId = resolveRawResource(mediaName)
        if (mediaResId == 0) return

        statusText.text = "Ketuk untuk memutar tutorial"
        playButton.visibility = View.VISIBLE

        when (mediaType) {
            TutorialMediaType.MP4 -> configureMp4Tutorial(
                playerView = playerView,
                placeholder = placeholder,
                dimView = dimView,
                playButton = playButton,
                statusText = statusText,
                mediaResId = mediaResId
            )
            TutorialMediaType.GIF -> configureGifTutorial(
                placeholder = placeholder,
                dimView = dimView,
                playButton = playButton,
                statusText = statusText,
                mediaResId = mediaResId
            )
        }
    }

    private fun configureMp4Tutorial(
        playerView: PlayerView,
        placeholder: ImageView,
        dimView: View,
        playButton: ImageButton,
        statusText: TextView,
        mediaResId: Int
    ) {
        val context = requireContext()
        val uri = Uri.parse("android.resource://${context.packageName}/$mediaResId")
        val player = ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
        tutorialPlayer = player
        playerView.player = player

        fun startVideo() {
            placeholder.visibility = View.GONE
            dimView.visibility = View.GONE
            statusText.visibility = View.GONE
            playButton.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            player.play()
        }

        playButton.setOnClickListener { startVideo() }
        placeholder.setOnClickListener { startVideo() }
    }

    private fun configureGifTutorial(
        placeholder: ImageView,
        dimView: View,
        playButton: ImageButton,
        statusText: TextView,
        mediaResId: Int
    ) {
        runCatching {
            val source = ImageDecoder.createSource(resources, mediaResId)
            ImageDecoder.decodeDrawable(source)
        }.onSuccess { drawable ->
            placeholder.setImageDrawable(drawable)
            val animatedDrawable = drawable as? AnimatedImageDrawable
            if (animatedDrawable == null) {
                playButton.visibility = View.GONE
                statusText.visibility = View.GONE
                dimView.visibility = View.GONE
                return@onSuccess
            }
            tutorialGif = animatedDrawable.apply {
                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            }

            fun startGif() {
                dimView.visibility = View.GONE
                statusText.visibility = View.GONE
                playButton.visibility = View.GONE
                animatedDrawable.start()
            }

            playButton.setOnClickListener { startGif() }
            placeholder.setOnClickListener { startGif() }
        }.onFailure {
            playButton.visibility = View.GONE
            statusText.text = "Video tutorial belum tersedia"
        }
    }

    private fun resolveRawResource(resourceName: String): Int {
        if (resourceName.isBlank()) return 0
        return resources.getIdentifier(resourceName, "raw", requireContext().packageName)
    }

    private fun releaseTutorialMedia() {
        tutorialGif?.stop()
        tutorialGif = null
        tutorialPlayer?.release()
        tutorialPlayer = null
    }

    private fun createStepItem(number: Int, text: String): View {
        val inflater = LayoutInflater.from(requireContext())
        val item = inflater.inflate(R.layout.item_panduan_step, null)
        item.findViewById<TextView>(R.id.tv_step_number).text = number.toString()
        item.findViewById<TextView>(R.id.tv_step_text).text = text
        return item
    }

    private fun createBulletItem(text: String, isCorrect: Boolean): View {
        val inflater = LayoutInflater.from(requireContext())
        val item = inflater.inflate(R.layout.item_panduan_bullet, null)
        val tvBullet = item.findViewById<TextView>(R.id.tv_bullet_icon)
        val tvText = item.findViewById<TextView>(R.id.tv_bullet_text)
        tvBullet.text = "\u2022"
        tvBullet.setTextColor(
            if (isCorrect) {
                resources.getColor(R.color.success, null)
            } else {
                resources.getColor(R.color.primary, null)
            }
        )
        tvText.text = text
        return item
    }

    private fun createTipItem(text: String): View {
        val inflater = LayoutInflater.from(requireContext())
        val item = inflater.inflate(R.layout.item_panduan_tip, null)
        item.findViewById<TextView>(R.id.tv_tip_text).text = text
        return item
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releaseTutorialMedia()
        _binding = null
    }
}
