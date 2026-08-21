package com.ingreatsol.reconocimiento_manos_python

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream

// Estructura para almacenar cada foto individual con sus datos de escalado e inferencia
data class FotoProcesada(
    val uri: Uri,
    val anchoOriginal: Int,
    val altoOriginal: Int,
    val apiResponse: ApiResponse
)

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerModos: Spinner
    private lateinit var btnAccionPrincipal: Button
    private lateinit var btnAlternarModo: Button
    private lateinit var btnAnteriorFoto: Button
    private lateinit var btnSiguienteFoto: Button
    private lateinit var btnLimpiarEscaneo: Button // NUEVO VINCULO
    private lateinit var ivPreview: ImageView
    private lateinit var tvResultados: TextView
    private lateinit var txtContadorFotos: TextView
    private lateinit var txtResultadoSuma: TextView
    private lateinit var resatadorView: ResaltadorView
    private lateinit var switchLocal: com.google.android.material.switchmaterial.SwitchMaterial

    // Visor nativo oficial para CameraX
    private lateinit var previewViewCameraX: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var estaProcesandoFrameEnVivo = false
    private var ultimoTimestampProcesado = 0L

    private lateinit var apiService: YoloApiService
    private var yoloOnnxDetector: YoloOnnxDetector? = null

    val BASE_URL = "http://192.168.0.117:8000/"

    // Historial del lote de imágenes actual
    private val loteFotosProcesadas = mutableListOf<FotoProcesada>()
    private var indiceFotoActualVisualizada = 0

    private var fotoActualContador = 0
    private var totalFotosSeleccionadas = 0
    private var modoMostrarCuadros = true
    private var modoSeleccionado = 0

    // Acumuladores globales para el texto final
    private var acumuladoManosTotales = 0
    private var maximoRacimosVistos = 0
    private val cintasUnicas = mutableSetOf<String>()

    // Registro global de IDs de manos y racimos vistos en el recorrido 360°
    private val manosRegistradasIds = mutableSetOf<Int>()
    private val racimosRegistradosIds = mutableSetOf<Int>()

    // NUEVO CONTADOR: Guarda permanentemente el pico máximo de manos en un solo instante
    private var maxManosEnUnInstante360 = 0

    // Lanzador oficial para solicitar el permiso del sistema si el usuario acepta en el SweetAlert
    private val solicitarPermisoCamaraLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { esConcedido: Boolean ->
        if (esConcedido) {
            ejecutarInicializacionCameraX()
        } else {
            cn.pedant.SweetAlert.SweetAlertDialog(this, cn.pedant.SweetAlert.SweetAlertDialog.ERROR_TYPE)
                .setTitleText("Permiso Denegado")
                .setContentText("No se puede usar el modo en tiempo real sin acceso a la cámara.")
                .setConfirmText("Entendido")
                .show()
        }
    }

    private val seleccionarUnaImagenLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            prepararContenedoresParaImagen()
            ocultarBotonesNavegacion()
            ivPreview.setImageURI(it)

            totalFotosSeleccionadas = 1
            fotoActualContador = 0
            acumuladoManosTotales = 0
            maximoRacimosVistos = 0
            cintasUnicas.clear()
            loteFotosProcesadas.clear()

            procesarImagen(it)
        }
    }

    private fun procesarImagen(uri: Uri) {
        if (switchLocal.isChecked && yoloOnnxDetector != null) {
            procesarImagenLocalOnnx(uri)
        } else {
            subirImagenAlServidor(uri)
        }
    }

    private fun procesarImagenLocalOnnx(uri: Uri) {
        val inputStream = contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        if (bitmap != null) {
            val apiResult = yoloOnnxDetector!!.detect(bitmap)
            gestionarResultadoLocal(uri, apiResult)
        }
    }

    private fun gestionarResultadoLocal(uri: Uri, apiResult: ApiResponse) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        var anchoOriginal = options.outWidth
        var altoOriginal = options.outHeight

        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientacion = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                if (orientacion == ExifInterface.ORIENTATION_ROTATE_90 || orientacion == ExifInterface.ORIENTATION_ROTATE_270) {
                    val temp = anchoOriginal
                    anchoOriginal = altoOriginal
                    altoOriginal = temp
                }
            }
        } catch (e: Exception) {}

        fotoActualContador++
        loteFotosProcesadas.add(FotoProcesada(uri, anchoOriginal, altoOriginal, apiResult))

        acumuladoManosTotales += apiResult.manos
        if (apiResult.racimos > maximoRacimosVistos) maximoRacimosVistos = apiResult.racimos

        actualizarPantallaResultados()

        if (modoSeleccionado == 1) {
            txtContadorFotos.text = "Fotos procesadas: $fotoActualContador / $totalFotosSeleccionadas"
        } else {
            txtContadorFotos.text = "Fotos procesadas: 1 / 1"
        }

        if (fotoActualContador >= totalFotosSeleccionadas) {
            calcularConsolidadoRacimo()
            indiceFotoActualVisualizada = 0
            desplegarFotoEnPantalla(0)
            if (modoSeleccionado == 1 && loteFotosProcesadas.size > 1) {
                btnAnteriorFoto.visibility = View.VISIBLE
                btnSiguienteFoto.visibility = View.VISIBLE
            }
        }
    }

    private val seleccionarMultiImagenesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            if (uris.size < 2 || uris.size > 4) {
                Toast.makeText(this, "Por favor, selecciona entre 2 and 4 fotos.", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            prepararContenedoresParaImagen()
            ocultarBotonesNavegacion()

            totalFotosSeleccionadas = uris.size
            fotoActualContador = 0
            indiceFotoActualVisualizada = 0
            acumuladoManosTotales = 0
            maximoRacimosVistos = 0
            cintasUnicas.clear()
            loteFotosProcesadas.clear()

            txtContadorFotos.text = "Fotos procesadas: 0 / $totalFotosSeleccionadas"
            txtResultadoSuma.text = "Procesando lote de fotos en paralelo..."

            ivPreview.setImageURI(uris[0]) // Mostrar la primera mientras cargan

            for (uri in uris) {
                procesarImagen(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerModos = findViewById(R.id.spinnerModos)
        btnAccionPrincipal = findViewById(R.id.btnAccionPrincipal)
        btnAlternarModo = findViewById(R.id.btnAlternarModo)
        btnAnteriorFoto = findViewById(R.id.btnAnteriorFoto)
        btnSiguienteFoto = findViewById(R.id.btnSiguienteFoto)
        btnLimpiarEscaneo = findViewById(R.id.btnLimpiarEscaneo) // ASIGNACIÓN DEL NUEVO BOTÓN
        ivPreview = findViewById(R.id.ivPreview)
        tvResultados = findViewById(R.id.tvResultados)
        txtContadorFotos = findViewById(R.id.txtContadorFotos)
        txtResultadoSuma = findViewById(R.id.txtResultadoSuma)
        resatadorView = findViewById(R.id.resaltadorView)
        switchLocal = findViewById(R.id.switchLocal)

        // Inicializar detector local
        try {
            yoloOnnxDetector = YoloOnnxDetector(this)
        } catch (e: Exception) {
            Log.e("ONNX", "Error inicializando detector ONNX", e)
        }

        // Vincular el visor de CameraX
        previewViewCameraX = findViewById(R.id.previewViewCameraX)

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        apiService = retrofit.create(YoloApiService::class.java)

        val opcionesModos = arrayOf("1. Subir 1 sola foto", "2. Modo Multifoto (2-4 fotos)", "3. Tiempo Real / 360°")
        val adapterSpinner = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opcionesModos)
        spinnerModos.adapter = adapterSpinner

        spinnerModos.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                modoSeleccionado = position
                ocultarBotonesNavegacion()
                apagarCameraX() // Detener la cámara si se cambia de modo

                // Reiniciar estados del modo 360° al cambiar de opción
                manosRegistradasIds.clear()
                maxManosEnUnInstante360 = 0
                btnLimpiarEscaneo.visibility = View.GONE // Se oculta por defecto en modos de foto estática

                when (position) {
                    0 -> {
                        btnAccionPrincipal.text = "Seleccionar 1 Foto"
                        txtContadorFotos.text = "Modo: Foto Única"
                        prepararContenedoresParaImagen()
                    }
                    1 -> {
                        btnAccionPrincipal.text = "Seleccionar 2 a 4 Fotos"
                        txtContadorFotos.text = "Modo: Multifoto"
                        prepararContenedoresParaImagen()
                    }
                    2 -> {
                        btnAccionPrincipal.text = "Iniciar Cámara en Vivo (360°)"
                        txtContadorFotos.text = "Modo: Streaming Tiempo Real"
                        ivPreview.visibility = View.GONE
                        btnLimpiarEscaneo.visibility = View.VISIBLE // Habilitar únicamente en Streaming / 360°
                        resatadorView.setDetecciones(emptyList(), 1, 1, previewViewCameraX)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnAccionPrincipal.setOnClickListener {
            when (modoSeleccionado) {
                0 -> seleccionarUnaImagenLauncher.launch("image/*")
                1 -> seleccionarMultiImagenesLauncher.launch("image/*")
                2 -> arrancarStreamingTiempoReal()
            }
        }

        // LÓGICA DEL NUEVO BOTÓN: Reinicia por completo los acumuladores del escaneo actual
        btnLimpiarEscaneo.setOnClickListener {
            manosRegistradasIds.clear()
            racimosRegistradosIds.clear()
            maxManosEnUnInstante360 = 0
            yoloOnnxDetector?.resetTracker()
            resatadorView.setDetecciones(emptyList(), 1, 1, previewViewCameraX)
            txtContadorFotos.text = "Manos en este instante (Máx): 0"
            actualizarResultadosEnVivo(0, 0, 0, "Ninguno")
            Toast.makeText(this, "Escaneo limpio. ¡Listo para registrar un nuevo racimo!", Toast.LENGTH_SHORT).show()
        }

        btnAlternarModo.setOnClickListener {
            modoMostrarCuadros = !modoMostrarCuadros
            resatadorView.visibility = if (modoMostrarCuadros) View.VISIBLE else View.GONE
            btnAlternarModo.text = if (modoMostrarCuadros) "Ocultar Cuadros" else "Mostrar Cuadros"
        }

        btnAnteriorFoto.setOnClickListener {
            if (loteFotosProcesadas.isNotEmpty()) {
                indiceFotoActualVisualizada = (indiceFotoActualVisualizada - 1 + loteFotosProcesadas.size) % loteFotosProcesadas.size
                desplegarFotoEnPantalla(indiceFotoActualVisualizada)
            }
        }

        btnSiguienteFoto.setOnClickListener {
            if (loteFotosProcesadas.isNotEmpty()) {
                indiceFotoActualVisualizada = (indiceFotoActualVisualizada + 1) % loteFotosProcesadas.size
                desplegarFotoEnPantalla(indiceFotoActualVisualizada)
            }
        }
    }

    private fun prepararContenedoresParaImagen() {
        previewViewCameraX.visibility = View.GONE
        ivPreview.visibility = View.VISIBLE
        resatadorView.visibility = if (modoMostrarCuadros) View.VISIBLE else View.GONE
        btnAlternarModo.visibility = View.VISIBLE
    }

    private fun ocultarBotonesNavegacion() {
        btnAnteriorFoto.visibility = View.GONE
        btnSiguienteFoto.visibility = View.GONE
    }

    private fun arrancarStreamingTiempoReal() {
        val permisoCamara = android.Manifest.permission.CAMERA

        if (ContextCompat.checkSelfPermission(this, permisoCamara) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ejecutarInicializacionCameraX()
        } else {
            val sweetAlert = cn.pedant.SweetAlert.SweetAlertDialog(this, cn.pedant.SweetAlert.SweetAlertDialog.WARNING_TYPE)
            sweetAlert.setTitleText("Permiso Requerido")
                .setContentText("Para escanear el racimo en vivo y detectar las manos, necesitamos acceso a la cámara de tu celular.")
                .setConfirmText("Conceder Permiso")
                .setCancelText("Cancelar")
                .showCancelButton(true)
                .setConfirmClickListener { sDialog ->
                    sDialog.dismissWithAnimation()
                    solicitarPermisoCamaraLauncher.launch(permisoCamara)
                }
                .setCancelClickListener { sDialog ->
                    sDialog.dismissWithAnimation()
                }
                .show()
        }
    }

    private fun ejecutarInicializacionCameraX() {
        ivPreview.visibility = View.GONE
        ocultarBotonesNavegacion()

        previewViewCameraX.visibility = View.VISIBLE
        resatadorView.visibility = if (modoMostrarCuadros) View.VISIBLE else View.GONE
        btnAlternarModo.visibility = View.VISIBLE

        tvResultados.text = "Iniciando hardware de cámara nativa..."
        txtResultadoSuma.text = "Enfoca el racimo con tu celular"
        txtContadorFotos.text = "Modo: Tiempo Real (CameraX)"

        manosRegistradasIds.clear()
        racimosRegistradosIds.clear()
        maxManosEnUnInstante360 = 0
        yoloOnnxDetector?.resetTracker()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewViewCameraX.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                val ahora = System.currentTimeMillis()
                
                // Si es local, limitamos a ~10 FPS (150ms) para evitar sobrecalentamiento
                // Si es remoto, la latencia de red ya hace de limitador natural
                val delayMinimo = if (switchLocal.isChecked) 150 else 0 

                if (!estaProcesandoFrameEnVivo && modoSeleccionado == 2 && (ahora - ultimoTimestampProcesado) > delayMinimo) {
                    estaProcesandoFrameEnVivo = true
                    ultimoTimestampProcesado = ahora

                    val bitmap = previewViewCameraX.bitmap
                    if (bitmap != null) {
                        if (switchLocal.isChecked && yoloOnnxDetector != null) {
                            val apiResult = yoloOnnxDetector!!.detect(bitmap)
                            runOnUiThread {
                                resatadorView.setDetecciones(apiResult.detecciones, bitmap.width, bitmap.height, previewViewCameraX)
                                if (apiResult.manos > maxManosEnUnInstante360) {
                                    maxManosEnUnInstante360 = apiResult.manos
                                }
                                // SOLO AGREGAR IDS PARA EL CONTEO ÚNICO POR CLASE
                                for (det in apiResult.detecciones) {
                                    if (det.clase == "mano") {
                                        det.id?.let { manosRegistradasIds.add(it) }
                                    } else if (det.clase == "racimo") {
                                        det.id?.let { racimosRegistradosIds.add(it) }
                                    }
                                }
                                val picoAjustado = if (maxManosEnUnInstante360 > 0) maxManosEnUnInstante360 + 3 else 0
                                txtContadorFotos.text = "Manos en este instante (Máx): $picoAjustado"
                                actualizarResultadosEnVivo(maxManosEnUnInstante360, manosRegistradasIds.size, racimosRegistradosIds.size, "ONNX")
                                estaProcesandoFrameEnVivo = false
                                imageProxy.close()
                            }
                        } else {
                            try {
                                val archivoTemporal = File(cacheDir, "live_frame.png")
                                FileOutputStream(archivoTemporal).use { out ->
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 65, out)
                                }

                                val requestFile = archivoTemporal.asRequestBody("image/*".toMediaTypeOrNull())
                                val body = MultipartBody.Part.createFormData("file", archivoTemporal.name, requestFile)

                                apiService.enviarImagen(body).enqueue(object : Callback<ApiResponse> {
                                    override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                                        if (response.isSuccessful && response.body() != null && modoSeleccionado == 2) {
                                            val apiResult = response.body()!!
                                            if (!apiResult.error) {
                                                resatadorView.setDetecciones(apiResult.detecciones, bitmap.width, bitmap.height, previewViewCameraX)
                                                
                                                if (apiResult.manos > maxManosEnUnInstante360) {
                                                    maxManosEnUnInstante360 = apiResult.manos
                                                }
                                                // SOLO AGREGAR IDS PARA EL CONTEO ÚNICO POR CLASE
                                                for (det in apiResult.detecciones) {
                                                    if (det.clase == "mano") {
                                                        det.id?.let { manosRegistradasIds.add(it) }
                                                    } else if (det.clase == "racimo") {
                                                        det.id?.let { racimosRegistradosIds.add(it) }
                                                    }
                                                }
                                                val picoAjustado = if (maxManosEnUnInstante360 > 0) maxManosEnUnInstante360 + 3 else 0
                                                txtContadorFotos.text = "Manos en este instante (Máx): $picoAjustado"
                                                actualizarResultadosEnVivo(maxManosEnUnInstante360, manosRegistradasIds.size, racimosRegistradosIds.size, apiResult.color_cinta)
                                            }
                                        }
                                        estaProcesandoFrameEnVivo = false
                                        imageProxy.close()
                                    }

                                    override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                                        estaProcesandoFrameEnVivo = false
                                        imageProxy.close()
                                    }
                                })
                            } catch (e: Exception) {
                                estaProcesandoFrameEnVivo = false
                                imageProxy.close()
                            }
                        }
                    } else {
                        imageProxy.close()
                    }
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e("CameraX", "Fallo al enlazar casos de uso", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun apagarCameraX() {
        cameraProvider?.unbindAll()
        previewViewCameraX.visibility = View.GONE
    }

    private fun actualizarResultadosEnVivo(manosInstante: Int, totalManos: Int, totalRacimos: Int, cinta: String?) {
        val cintaTexto = if (!cinta.isNullOrBlank()) cinta else "Ninguno"
        
        // Ajuste solicitado: +3 a los resultados de manos si se detecta al menos una
        val manosInstanteAjustado = if (manosInstante > 0) manosInstante + 3 else 0
        val totalManosAjustado = if (totalManos > 0) totalManos + 3 else 0

        tvResultados.text = """
            Resultados (Tiempo Real / 360°):
            ----------------------------------
            Pico Máx Manos en Pantalla: $manosInstanteAjustado
            Total Manos Únicas: $totalManosAjustado
            Total Racimos Únicos: $totalRacimos
            Color de cinta: $cintaTexto
        """.trimIndent()
    }

    private fun subirImagenAlServidor(uri: Uri) {
        val file = uriToFile(uri) ?: return

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        var anchoOriginal = options.outWidth
        var altoOriginal = options.outHeight

        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val orientacion = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                if (orientacion == ExifInterface.ORIENTATION_ROTATE_90 || orientacion == ExifInterface.ORIENTATION_ROTATE_270) {
                    val temp = anchoOriginal
                    anchoOriginal = altoOriginal
                    altoOriginal = temp
                }
            }
        } catch (e: Exception) { Log.e("ExifError", "Error EXIF", e) }

        val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)

        apiService.enviarImagen(body).enqueue(object : Callback<ApiResponse> {
            override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val apiResult = response.body()!!

                    if (!apiResult.error) {
                        fotoActualContador++

                        loteFotosProcesadas.add(FotoProcesada(uri, anchoOriginal, altoOriginal, apiResult))

                        acumuladoManosTotales += apiResult.manos
                        if (apiResult.racimos > maximoRacimosVistos) {
                            maximoRacimosVistos = apiResult.racimos
                        }
                        if (!apiResult.color_cinta.isNullOrBlank() && apiResult.color_cinta != "Ninguno") {
                            cintasUnicas.add(apiResult.color_cinta)
                        }

                        actualizarPantallaResultados()

                        if (modoSeleccionado == 1) {
                            txtContadorFotos.text = "Fotos procesadas: $fotoActualContador / $totalFotosSeleccionadas"
                        } else {
                            txtContadorFotos.text = "Fotos procesadas: 1 / 1"
                        }

                        if (fotoActualContador >= totalFotosSeleccionadas) {
                            calcularConsolidadoRacimo()
                            indiceFotoActualVisualizada = 0
                            desplegarFotoEnPantalla(0)

                            if (modoSeleccionado == 1 && loteFotosProcesadas.size > 1) {
                                btnAnteriorFoto.visibility = View.VISIBLE
                                btnSiguienteFoto.visibility = View.VISIBLE
                            }
                        }

                    } else { tvResultados.text = "Error interno en el script de Python." }
                } else { tvResultados.text = "Error de respuesta: ${response.code()}" }
            }

            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                tvResultados.text = "Error de conexión con FastAPI."
            }
        })
    }

    private fun desplegarFotoEnPantalla(indice: Int) {
        if (indice in 0 until loteFotosProcesadas.size) {
            val foto = loteFotosProcesadas[indice]

            ivPreview.setImageURI(foto.uri)

            if (modoMostrarCuadros) {
                resatadorView.setDetecciones(
                    foto.apiResponse.detecciones,
                    foto.anchoOriginal,
                    foto.altoOriginal,
                    ivPreview
                )
            }

            if (modoSeleccionado == 1) {
                txtContadorFotos.text = "Viendo foto: ${indice + 1} / ${loteFotosProcesadas.size} (Lote total)"
            }
        }
    }

    private fun actualizarPantallaResultados() {
        val listaCintas = cintasUnicas.joinToString(", ").ifEmpty { "Ninguno" }
        tvResultados.text = """
            Resultados:
            ----------------------------------
            Racimos detectados: $maximoRacimosVistos
            Manos Detectadas: $acumuladoManosTotales
            Color de cinta: $listaCintas
        """.trimIndent()
    }

    private fun calcularConsolidadoRacimo() {
        val promedioManosFinal = Math.round(acumuladoManosTotales.toDouble() / totalFotosSeleccionadas).toInt()
        if (modoSeleccionado == 1) {
            txtResultadoSuma.text = """
                Análisis de Consolidación Terminado ($totalFotosSeleccionadas fotos)
                =========================================
                • Promedio Estimado del Racimo: $promedioManosFinal manos.
                • Total Acumulado en Perspectivas: $acumuladoManosTotales manos vistas.
            """.trimIndent()
        } else {
            txtResultadoSuma.text = """
                Análisis de Foto Única Terminado
                =========================================
                • Total de Manos Detectadas: $acumuladoManosTotales manos.
            """.trimIndent()
        }
    }

    private fun uriToFile(uri: Uri): File? {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val tempFile = File.createTempFile("upload_", ".png", cacheDir)
            val outputStream = FileOutputStream(tempFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            return tempFile
        } catch (e: Exception) { return null }
    }

    override fun onDestroy() {
        super.onDestroy()
        apagarCameraX()
    }
}