package com.selxo.rougo.windows

import java.awt.Color
import java.awt.Graphics2D
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

object DesktopNotifier {
    private val trayIcon: TrayIcon? by lazy {
        runCatching {
            if (!SystemTray.isSupported()) return@runCatching null
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            drawTrayIcon(g)
            g.dispose()
            TrayIcon(image, "Rougo").apply {
                isImageAutoSize = true
                SystemTray.getSystemTray().add(this)
            }
        }.getOrNull()
    }

    fun info(title: String, message: String) {
        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }

    fun warning(title: String, message: String) {
        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.WARNING)
    }

    private fun drawTrayIcon(g: Graphics2D) {
        g.color = Color(0x58, 0x5D, 0xDB)
        g.fillOval(1, 1, 14, 14)
        g.color = Color.WHITE
        g.drawString("R", 4, 12)
    }
}
