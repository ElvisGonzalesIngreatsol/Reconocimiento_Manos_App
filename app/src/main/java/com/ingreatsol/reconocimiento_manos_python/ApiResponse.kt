package com.ingreatsol.reconocimiento_manos_python

data class ApiResponse(
    val error: Boolean,
    val racimos: Int,
    val manos: Int,
    val color_cinta: String,
    val detecciones: List<Deteccion>
)

data class Deteccion(
    val clase: String,
    val confianza: Float,
    val box: List<Float>, // [xmin, ymin, xmax, ymax]
    val id: Int? = null
)