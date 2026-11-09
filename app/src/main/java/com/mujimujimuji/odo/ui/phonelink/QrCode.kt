package com.mujimujimuji.odo.ui.phonelink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mujimujimuji.odo.R
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSize
import com.mujimujimuji.odo.ui.theme.OdoSpace

/** QR 모듈 행렬. 기기 안에서 zxing으로 만들고 어디에도 보내지 않는다. */
class QrMatrix(val size: Int, private val bits: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = bits[y * size + x]

    companion object {
        fun encode(payload: String): QrMatrix {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
            val m = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
            val n = m.width
            return QrMatrix(n, BooleanArray(n * n) { i -> m.get(i % n, i / n) })
        }
    }
}

/**
 * QR 판(설계서 §5.6): `qrPlate` 272dp 모서리 16, 안쪽 240dp에 `qrModule` 모듈. 밝은 판에 어두운 모듈 — 반전(흰 QR) 금지.
 * 모듈은 소수점 경계가 비치지 않게 0.5px 겹쳐 그린다. [dimmed]는 확인됨 1.5 s 동안 판을 그대로 두고 모듈만 흐리게 한다
 * (그 위에 체크를 겹친다). 흐린 판은 찍을 대상이 아니라 의미 설명을 붙이지 않는다.
 */
@Composable
fun QrPlate(payload: String, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val c = LocalOdoColors.current
    val matrix = remember(payload) { QrMatrix.encode(payload) }
    val desc = stringResource(R.string.phone_qr_desc)
    val module = if (dimmed) c.qrModule.copy(alpha = DIMMED_MODULE_ALPHA) else c.qrModule
    Box(
        modifier
            .size(OdoSize.qr + OdoSpace.l * 2)
            .background(c.qrPlate, OdoShape.panel)
            .padding(OdoSpace.l)
            .then(if (dimmed) Modifier else Modifier.semantics { contentDescription = desc }),
    ) {
        Canvas(Modifier.size(OdoSize.qr)) {
            val n = matrix.size
            val cell = size.width / n
            val box = Size(cell + 0.5f, cell + 0.5f)
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (matrix[x, y]) drawRect(module, Offset(x * cell, y * cell), box)
                }
            }
        }
    }
}

private const val DIMMED_MODULE_ALPHA = 0.18f
