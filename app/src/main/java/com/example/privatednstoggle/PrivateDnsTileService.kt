package com.example.privatednstoggle

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Служба плитки быстрых настроек (Quick Settings Tile) в шторке уведомлений Android.
 *
 * - Одиночное нажатие: мгновенно переключает Частный DNS между "Выкл." (off)
 *   и "Имя хоста провайдера DNS" (hostname) с последним выбранным сервером.
 * - Долгое нажатие: открывает главное окно приложения (задается через intent-filter
 *   ACTION_QS_TILE_PREFERENCES в AndroidManifest.xml).
 */
class PrivateDnsTileService : TileService() {

    private lateinit var repository: DnsRepository

    override fun onCreate() {
        super.onCreate()
        repository = DnsRepository(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileUi()
    }

    override fun onClick() {
        super.onClick()

        if (!repository.hasWriteSecureSettingsPermission()) {
            Toast.makeText(
                applicationContext,
                "Требуется выдать разрешение WRITE_SECURE_SETTINGS (инструкция в приложении)",
                Toast.LENGTH_LONG
            ).show()
            openMainActivityFromTile()
            return
        }

        val togglework = repository.togglePrivateDns()
        togglework.onFailure { err ->
            Toast.makeText(
                applicationContext,
                err.localizedMessage ?: "Ошибка переключения DNS",
                Toast.LENGTH_SHORT
            ).show()
        }

        updateTileUi()
    }

    private fun updateTileUi() {
        val tile = qsTile ?: return
        val state = repository.getState()

        if (!state.hasPermission) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Частный DNS"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Нужно разрешение"
            }
            tile.updateTile()
            return
        }

        when (state.mode) {
            PrivateDnsMode.HOSTNAME -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Частный DNS"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = state.currentHostname
                }
            }
            PrivateDnsMode.OFF -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Частный DNS"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Выкл."
                }
            }
        }

        tile.updateTile()
    }

    private fun openMainActivityFromTile() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /**
         * Принудительное обновление состояния плитки при изменении настроек внутри приложения.
         */
        fun requestTileSync(context: Context) {
            requestListeningState(
                context,
                ComponentName(context, PrivateDnsTileService::class.java)
            )
        }
    }
}
