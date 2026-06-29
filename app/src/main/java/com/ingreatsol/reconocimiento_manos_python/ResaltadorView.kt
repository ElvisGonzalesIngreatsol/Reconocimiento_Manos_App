package com.ingreatsol.reconocimiento_manos_python

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ResaltadorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detecciones: List<Deteccion> = emptyList()
    private var imgAnchoOriginal = 1
    private var imgAltoOriginal = 1

    // CORRECCIÓN: Cambiado de ImageView? a View? para dar soporte a PreviewView y a ImageView
    private var viewReferencia: View? = null

    private val paintCuadro = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val paintTexto = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        style = Paint.Style.FILL
        targetToShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val paintFondoTexto = Paint().apply {
        color = Color.parseColor("#80000000") // Fondo semitransparente para legibilidad
        style = Paint.Style.FILL
    }

    // CORRECCIÓN: Cambiado el parámetro 'iv: ImageView' por 'vistaRef: View'
    fun setDetecciones(nuevasDetecciones: List<Deteccion>, anchoOrig: Int, altoOrig: Int, vistaRef: View) {
        this.detecciones = nuevasDetecciones
        this.imgAnchoOriginal = anchoOrig
        this.imgAltoOriginal = altoOrig
        this.viewReferencia = vistaRef
        invalidate() // Forzar redibujado en pantalla
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Validamos que exista la vista de referencia
        val vista = viewReferencia ?: return

        // Obtener las dimensiones del contenedor activo en pantalla (ImageView o PreviewView)
        val viewAncho = vista.width.toFloat()
        val viewAlto = vista.height.toFloat()

        // Calcular la escala de ajuste basada en la regla FitCenter
        val escalaX = viewAncho / imgAnchoOriginal
        val escalaY = viewAlto / imgAltoOriginal
        val escalaFinal = Math.min(escalaX, escalaY)

        // Determinar los espacios vacíos exactos (márgenes sobrantes) a los lados y arriba/abajo
        val traslacionX = (viewAncho - (imgAnchoOriginal * escalaFinal)) / 2f
        val traslacionY = (viewAlto - (imgAltoOriginal * escalaFinal)) / 2f

        // Mapear y pintar cada recuadro verde de YOLO
        for (det in detecciones) {
            val box = det.box // Coordenadas del JSON: [xmin, ymin, xmax, ymax]

            // Transformación de escala de imagen original a píxeles físicos del celular
            val xMinFinal = (box[0] * escalaFinal) + traslacionX
            val yMinFinal = (box[1] * escalaFinal) + traslacionY
            val xMaxFinal = (box[2] * escalaFinal) + traslacionX
            val yMaxFinal = (box[3] * escalaFinal) + traslacionY

            // Dibujar recuadro verde sobre la mano detectada
            canvas.drawRect(xMinFinal, yMinFinal, xMaxFinal, yMaxFinal, paintCuadro)

            // Concatenar el ID si viene presente en los datos del Object Tracker
            val idTexto = if (det.id != null) " ID: ${det.id}" else ""
            val textoEtiqueta = "${det.clase} ${(det.confianza * 100).toInt()}%$idTexto"
            val anchoTexto = paintTexto.measureText(textoEtiqueta)

            // Dibujar el fondo oscuro detrás de la etiqueta y el texto informativo
            canvas.drawRect(xMinFinal, yMinFinal - 45f, xMinFinal + anchoTexto + 15f, yMinFinal, paintFondoTexto)
            canvas.drawText(textoEtiqueta, xMinFinal + 8f, yMinFinal - 10f, paintTexto)
        }
    }

    private fun Paint.targetToShadowLayer(radius: Float, dx: Float, dy: Float, color: Int) {
        this.setShadowLayer(radius, dx, dy, color)
    }
}