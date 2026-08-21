package com.ingreatsol.reconocimiento_manos_python

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Collections

class YoloOnnxDetector(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputSize = 640
    private val labels = listOf("racimo", "mano") // Ajustar según las clases reales del modelo

    // Variables para el tracking
    private var nextId = 1
    private var previousDetections = mutableListOf<Deteccion>()
    private val trackingIouThreshold = 0.15f // Bajamos a 0.15 para video rápido

    init {
        val modelBytes = context.assets.open("best.onnx").readBytes()
        session = env.createSession(modelBytes)
    }

    fun resetTracker() {
        nextId = 1
        previousDetections.clear()
    }

    fun detect(bitmap: Bitmap): ApiResponse {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val imgData = preprocess(resizedBitmap)

        val inputName = session.inputNames.first()
        val inputTensor = OnnxTensor.createTensor(env, imgData, longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
        
        val results = session.run(java.util.Collections.singletonMap(inputName, inputTensor))
        val outputTensor = results[0] as OnnxTensor
        
        val shape = outputTensor.info.shape
        android.util.Log.d("ONNX_DEBUG", "Output Shape: ${shape.joinToString("x")}")

        // El formato estándar YOLOv8 es [1, 4 + num_classes, 8400]
        // Pero si es [1, 8400, 4 + num_classes], necesitamos transponer la lógica
        val isTransposed = shape.size == 3 && shape[1] > shape[2] 
        
        val rawOutput = outputTensor.floatBuffer
        val numElements: Int
        val numCandidates: Int

        if (isTransposed) {
            numCandidates = shape[1].toInt()
            numElements = shape[2].toInt()
        } else {
            numElements = shape[1].toInt()
            numCandidates = shape[2].toInt()
        }

        return postprocess(rawOutput, numCandidates, numElements, bitmap.width, bitmap.height, isTransposed)
    }

    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val buffer = FloatBuffer.allocate(1 * 3 * inputSize * inputSize)
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (c in 0 until 3) {
            for (i in 0 until inputSize * inputSize) {
                val pixel = pixels[i]
                val value = when (c) {
                    0 -> (pixel shr 16 and 0xFF) / 255f
                    1 -> (pixel shr 8 and 0xFF) / 255f
                    else -> (pixel and 0xFF) / 255f
                }
                buffer.put(value)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun postprocess(buffer: java.nio.FloatBuffer, numCandidates: Int, numElements: Int, origWidth: Int, origHeight: Int, isTransposed: Boolean): ApiResponse {
        val detections = mutableListOf<Deteccion>()
        val confThreshold = 0.20f // Bajamos umbral para ver si detecta algo
        
        val output = FloatArray(numCandidates * numElements)
        buffer.get(output)

        for (i in 0 until numCandidates) {
            var maxConf = 0f
            var classId = -1
            
            val numClasses = numElements - 4

            for (c in 0 until numClasses) {
                val index = if (isTransposed) {
                    i * numElements + (4 + c)
                } else {
                    (4 + c) * numCandidates + i
                }
                
                val conf = output[index]
                if (conf > maxConf) {
                    maxConf = conf
                    classId = c
                }
            }
            
            if (maxConf > confThreshold && classId < labels.size) {
                val cx: Float
                val cy: Float
                val w: Float
                val h: Float

                if (isTransposed) {
                    cx = output[i * numElements + 0]
                    cy = output[i * numElements + 1]
                    w = output[i * numElements + 2]
                    h = output[i * numElements + 3]
                } else {
                    cx = output[0 * numCandidates + i]
                    cy = output[1 * numCandidates + i]
                    w = output[2 * numCandidates + i]
                    h = output[3 * numCandidates + i]
                }
                
                val xmin = (cx - w / 2) * (origWidth.toFloat() / inputSize)
                val ymin = (cy - h / 2) * (origHeight.toFloat() / inputSize)
                val xmax = (cx + w / 2) * (origWidth.toFloat() / inputSize)
                val ymax = (cy + h / 2) * (origHeight.toFloat() / inputSize)
                
                detections.add(Deteccion(
                    clase = labels[classId],
                    confianza = maxConf,
                    box = listOf(xmin, ymin, xmax, ymax)
                ))
            }
        }
        
        val nmsDetections = applyNMS(detections)
        val trackedDetections = applyTracking(nmsDetections)

        return ApiResponse(
            error = false,
            racimos = trackedDetections.count { it.clase == "racimo" },
            manos = trackedDetections.count { it.clase == "mano" },
            color_cinta = "Local ONNX",
            detecciones = trackedDetections
        )
    }

    private fun applyTracking(currentDetections: List<Deteccion>): List<Deteccion> {
        val trackedDetections = mutableListOf<Deteccion>()
        val usedPreviousIndices = mutableSetOf<Int>()

        // 1. Intentar emparejar con las detecciones del frame anterior
        for (current in currentDetections) {
            var bestIou = 0f
            var bestIndex = -1

            for (i in previousDetections.indices) {
                if (i in usedPreviousIndices) continue
                if (previousDetections[i].clase != current.clase) continue

                val iou = calculateIoU(current.box, previousDetections[i].box)
                if (iou > bestIou && iou > trackingIouThreshold) {
                    bestIou = iou
                    bestIndex = i
                }
            }

            if (bestIndex != -1) {
                trackedDetections.add(current.copy(id = previousDetections[bestIndex].id))
                usedPreviousIndices.add(bestIndex)
            } else {
                // Si no hay coincidencia, asignar nuevo ID
                trackedDetections.add(current.copy(id = nextId++))
            }
        }

        // 2. Persistencia para video rápido:
        // Si una mano desaparece un frame, podríamos intentar "recordarla",
        // pero por ahora mantendremos la lógica simple con IoU muy bajo (0.15)
        // para que no pierda el ID ante movimientos bruscos.

        previousDetections = trackedDetections.toMutableList()
        return trackedDetections
    }

    private fun applyNMS(detections: List<Deteccion>): List<Deteccion> {
        val sorted = detections.sortedByDescending { it.confianza }.toMutableList()
        val selected = mutableListOf<Deteccion>()
        val iouThreshold = 0.45f

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)
            // CORRECCIÓN: NMS por clase. Solo elimina si son de la misma clase.
            // Esto evita que el cuadro del 'racimo' elimine los cuadros de las 'manos' dentro de él.
            sorted.removeAll { it.clase == best.clase && calculateIoU(best.box, it.box) > iouThreshold }
        }
        return selected
    }

    private fun calculateIoU(box1: List<Float>, box2: List<Float>): Float {
        val x1 = maxOf(box1[0], box2[0])
        val y1 = maxOf(box1[1], box2[1])
        val x2 = minOf(box1[2], box2[2])
        val y2 = minOf(box1[3], box2[3])

        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val area1 = (box1[2] - box1[0]) * (box1[3] - box1[1])
        val area2 = (box2[2] - box2[0]) * (box2[3] - box2[1])
        val union = area1 + area2 - intersection

        return if (union > 0) intersection / union else 0f
    }

    fun close() {
        session.close()
        env.close()
    }
}
