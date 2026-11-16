package com.wingua.spwsend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moriafly.salt.ui.Button
import com.moriafly.salt.ui.ControlSize
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.Text
import com.wingua.spwsend.net.PairingVerification

private object PairingSymbols {
    val entries by lazy {
        requireNotNull(javaClass.getResourceAsStream("/pairing/symbols.tsv"))
            .bufferedReader()
            .useLines { rows ->
                rows.map { row -> row.split('\t').let { it[0] to it[1].toInt(16) } }.toList()
            }
            .also { require(it.size == PairingVerification.ALPHABET_SIZE) }
    }
    val family by lazy {
        val bytes =
            requireNotNull(javaClass.getResourceAsStream("/pairing/pairing-symbols.otf")).use {
                it.readBytes()
            }
        FontFamily(Font(identity = "spw-send:pairing-symbols", data = bytes))
    }
}

@Composable
fun PairingVerificationView(verification: PairingVerification) {
    var showText by remember(verification.combined) { mutableStateOf(false) }
    val symbols = remember { runCatching { PairingSymbols.entries to PairingSymbols.family } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("请在另一台设备打开“核对图标”，按从左到右、从上到下的顺序核对。")
        Spacer(Modifier.height(12.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            if (showText) {
                SelectionContainer { Text(verification.combined) }
            } else {
                val loaded = symbols.getOrNull()
                if (loaded == null) {
                    Text("图标加载失败，请切换为文字核对。")
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        verification.indices.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.Center) {
                                row.forEach { index ->
                                    val (name, codepoint) = loaded.first[index]
                                    Box(
                                        Modifier.size(56.dp).semantics {
                                            contentDescription = name.replace('_', ' ')
                                        },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        BasicText(
                                            String(Character.toChars(codepoint)),
                                            style =
                                                TextStyle(
                                                    fontFamily = loaded.second,
                                                    fontSize = 32.sp,
                                                    color = SaltTheme.colors.text,
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { showText = !showText },
            text = if (showText) "显示图标" else "显示证书指纹",
            size = ControlSize.Small,
        )
    }
}
