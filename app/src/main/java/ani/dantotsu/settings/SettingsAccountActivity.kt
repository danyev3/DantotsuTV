package ani.dantotsu.settings

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.EditText
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.anilist.TVConnection
import ani.dantotsu.databinding.ActivitySettingsAccountsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.startMainActivity
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.launch

class SettingsAccountActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAccountsBinding
    private val restartMainActivity = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = startMainActivity(this@SettingsAccountActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this

        binding = ActivitySettingsAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            settingsAccountsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            accountSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

            settingsAccountHelp.setOnClickListener {
                CustomBottomDialog.newInstance().apply {
                    setTitleText(context.getString(R.string.account_help))
                    addView(
                        TextView(it.context).apply {
                            val markWon = Markwon.builder(it.context)
                                .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                            markWon.setMarkdown(this, context.getString(R.string.full_account_help))
                        }
                    )
                }.show(supportFragmentManager, "dialog")
            }

            fun reload() {
                if (Anilist.token != null) {
                    settingsAnilistLogin.setText(R.string.logout)
                    settingsAnilistLogin.setOnClickListener {
                        Anilist.removeSavedToken()
                        restartMainActivity.isEnabled = true
                        reload()
                    }
                    settingsAnilistUsername.visibility = View.VISIBLE
                    settingsAnilistUsername.text = Anilist.username
                    settingsAnilistAvatar.loadImage(Anilist.avatar)
                    settingsAnilistAvatar.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        val anilistLink = getString(
                            R.string.anilist_link,
                            PrefManager.getVal<String>(PrefName.AnilistUserName)
                        )
                        openLinkInBrowser(anilistLink)
                    }

                    settingsMALLoginRequired.visibility = View.GONE
                    settingsMALLogin.visibility = View.VISIBLE
                    settingsMALUsername.visibility = View.VISIBLE

                    if (MAL.token != null) {
                        settingsMALLogin.setText(R.string.logout)
                        settingsMALLogin.setOnClickListener {
                            MAL.removeSavedToken()
                            restartMainActivity.isEnabled = true
                            reload()
                        }
                        settingsMALUsername.visibility = View.VISIBLE
                        settingsMALUsername.text = MAL.username
                        settingsMALAvatar.loadImage(MAL.avatar)
                        settingsMALAvatar.setOnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            openLinkInBrowser(getString(R.string.myanilist_link, MAL.username))
                        }
                    } else {
                        settingsMALAvatar.setImageResource(R.drawable.ic_round_person_24)
                        settingsMALUsername.visibility = View.GONE
                        settingsMALLogin.setText(R.string.login)
                        settingsMALLogin.setOnClickListener {
                            MAL.loginIntent(context)
                        }
                    }

                    settingsTVLoginRequired.visibility = View.GONE
                    settingsLoginOnTV.visibility = View.VISIBLE

                } else {
                    settingsAnilistAvatar.setImageResource(R.drawable.ic_round_person_24)
                    settingsAnilistUsername.visibility = View.GONE
                    settingsRecyclerView.visibility = View.GONE
                    settingsAnilistLogin.setText(R.string.login)
                    settingsAnilistLogin.setOnClickListener {
                        Anilist.loginIntent(context)
                    }
                    settingsMALLoginRequired.visibility = View.VISIBLE
                    settingsMALLogin.visibility = View.GONE
                    settingsMALUsername.visibility = View.GONE

                    settingsTVLoginRequired.visibility = View.VISIBLE
                    settingsLoginOnTV.visibility = View.GONE
                }

                if (Discord.token != null) {
                    val id = PrefManager.getVal(PrefName.DiscordId, null as String?)
                    val avatar = PrefManager.getVal(PrefName.DiscordAvatar, null as String?)
                    val username = PrefManager.getVal(PrefName.DiscordUserName, null as String?)
                    if (id != null && avatar != null) {
                        settingsDiscordAvatar.loadImage("https://cdn.discordapp.com/avatars/$id/$avatar.png")
                        settingsDiscordAvatar.setOnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            val discordLink = getString(R.string.discord_link, id)
                            openLinkInBrowser(discordLink)
                        }
                    }
                    settingsDiscordUsername.visibility = View.VISIBLE
                    settingsDiscordUsername.text =
                        username ?: Discord.token?.replace(Regex("."), "*")
                    settingsDiscordLogin.setText(R.string.logout)
                    settingsDiscordLogin.setOnClickListener {
                        Discord.removeSavedToken(context)
                        restartMainActivity.isEnabled = true
                        reload()
                    }

                    settingsPresenceSwitcher.visibility = View.VISIBLE
                    var initialStatus = when (PrefManager.getVal<String>(PrefName.DiscordStatus)) {
                        "online" -> R.drawable.discord_status_online
                        "idle" -> R.drawable.discord_status_idle
                        "dnd" -> R.drawable.discord_status_dnd
                        "invisible" -> R.drawable.discord_status_invisible
                        else -> R.drawable.discord_status_online
                    }
                    settingsPresenceSwitcher.setImageResource(initialStatus)

                    val zoomInAnimation =
                        AnimationUtils.loadAnimation(context, R.anim.bounce_zoom)
                    settingsPresenceSwitcher.setOnClickListener {
                        var status = "online"
                        initialStatus = when (initialStatus) {
                            R.drawable.discord_status_online -> {
                                status = "idle"
                                R.drawable.discord_status_idle
                            }

                            R.drawable.discord_status_idle -> {
                                status = "dnd"
                                R.drawable.discord_status_dnd
                            }

                            R.drawable.discord_status_dnd -> {
                                status = "invisible"
                                R.drawable.discord_status_invisible
                            }

                            R.drawable.discord_status_invisible -> {
                                status = "online"
                                R.drawable.discord_status_online
                            }

                            else -> R.drawable.discord_status_online
                        }

                        PrefManager.setVal(PrefName.DiscordStatus, status)
                        settingsPresenceSwitcher.setImageResource(initialStatus)
                        settingsPresenceSwitcher.startAnimation(zoomInAnimation)
                    }
                    settingsPresenceSwitcher.setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        DiscordDialogFragment().show(supportFragmentManager, "dialog")
                        true
                    }
                } else {
                    settingsPresenceSwitcher.visibility = View.GONE
                    settingsDiscordAvatar.setImageResource(R.drawable.ic_round_person_24)
                    settingsDiscordUsername.visibility = View.GONE
                    settingsDiscordLogin.setText(R.string.login)
                    settingsDiscordLogin.setOnClickListener {
                        Discord.warning(context)
                            .show(supportFragmentManager, "dialog")
                    }
                }

                fun isWifiConnected(context: Context): Boolean {
                    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val networks = connectivityManager.allNetworks
                        for (network in networks) {
                            val capabilities = connectivityManager.getNetworkCapabilities(network)
                            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                return true
                            }
                        }
                        return false
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val network = connectivityManager.activeNetwork
                        val capabilities = connectivityManager.getNetworkCapabilities(network)
                        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    } else {
                        val activeNetworkInfo = connectivityManager.activeNetworkInfo
                        return activeNetworkInfo != null && activeNetworkInfo.type == ConnectivityManager.TYPE_WIFI && activeNetworkInfo.isConnected
                    }
                }

                settingsLoginOnTV.setOnClickListener { view ->
                    val isWifiConnected = isWifiConnected(view.context)
                    if (isWifiConnected) {
                        val dialog = CustomBottomDialog.newInstance().apply {
                            setTitleText(view.context.getString(R.string.login_on_tv))
                            addView(
                                TextView(view.context).apply {
                                    val markWon = Markwon.builder(view.context)
                                        .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                                    markWon.setMarkdown(
                                        this,
                                        view.context.getString(R.string.login_on_tv_desc)
                                    )
                                }
                            )

                            setTextInput(view.context.getString(R.string.insert_tv_code), 2, 3)

                            setNegativeButton(view.context.getString(R.string.cancel)) {
                                dismiss()
                            }

                            setPositiveButton(view.context.getString(R.string.login)) {
                                val dialogView = this@apply.view
                                val editText = dialogView?.findViewById<EditText>(R.id.bottomDialogCustomTextInput)
                                val tvCode = editText?.text?.toString()
                                if (!tvCode.isNullOrEmpty()) {
                                    val token = PrefManager.getVal(PrefName.AnilistToken, null as String?)
                                    Log.d("LoginDialog", "Token retrieved: $token")

                                    try {
                                        token?.let {
                                            TVConnection.sendDataToTV(view.context, tvCode)
                                            Log.d("LoginDialog", "Anilist.sendTokenToTV called")
                                            this@apply.dismiss()
                                        } ?: run {
                                            toast("Token not found")
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        Log.e("LoginDialog", "Exception when calling Anilist.sendTokenToTV", e)
                                        toast("Error: ${e.message}")
                                    }
                                } else {
                                    toast("Insert the TV code")
                                }
                            }
                        }
                        dialog.show(supportFragmentManager, "dialog")
                    } else {
                        toast(view.context.getString(R.string.login_must_use_wifi))
                    }
                }
            }
            reload()
        }
        binding.settingsRecyclerView.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 1,
                    name = getString(R.string.anilist_settings),
                    desc = getString(R.string.alsettings_desc),
                    icon = R.drawable.ic_anilist,
                    onClick = {
                        lifecycleScope.launch {
                            Anilist.query.getUserData()
                            startActivity(Intent(context, AnilistSettingsActivity::class.java))
                        }
                    },
                    isActivity = true
                ),
            )
        )
        binding.settingsRecyclerView.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

    }
}
