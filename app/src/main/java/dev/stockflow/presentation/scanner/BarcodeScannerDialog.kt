package dev.stockflow.presentation.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface as AndroidSurface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun BarcodeScannerDialog(onDismiss: () -> Unit, onBarcode: (String) -> String?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var barcode by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Найти товар по штрихкоду", style = MaterialTheme.typography.titleLarge)
                if (granted && cameraError == null) {
                    CameraPreview(
                        onBarcode = { code ->
                            barcode = code
                            message = onBarcode(code)
                            message == null
                        },
                        onError = { cameraError = it },
                    )
                } else {
                    Text(cameraError ?: "Разрешите доступ к камере или введите штрихкод вручную.")
                    if (granted && cameraError != null) {
                        TextButton(onClick = { cameraError = null }) { Text("Повторить запуск камеры") }
                    }
                    if (!granted) {
                        Row {
                            TextButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") }
                            TextButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")))
                            }) { Text("Настройки") }
                        }
                    }
                }
                OutlinedTextField(
                    value = barcode, onValueChange = { barcode = it; message = null },
                    label = { Text("Штрихкод") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (message != null) Text(checkNotNull(message), color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { message = onBarcode(barcode) }, enabled = barcode.isNotBlank()) { Text("Найти") }
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(onBarcode: (String) -> Boolean, onError: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val currentOnError by rememberUpdatedState(onError)
    val view = remember(context) {
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(240.dp))
    DisposableEffect(owner, view) {
        val active = AtomicBoolean(true)
        val delivered = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val scanner = try {
            BarcodeScanning.getClient()
        } catch (_: Exception) {
            executor.shutdown()
            currentOnError("Сканер недоступен. Введите код вручную.")
            return@DisposableEffect onDispose { }
        }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val future = try {
            ProcessCameraProvider.getInstance(context)
        } catch (_: Exception) {
            scanner.close()
            executor.shutdown()
            currentOnError("Камера недоступна. Введите код вручную.")
            return@DisposableEffect onDispose { }
        }
        var provider: ProcessCameraProvider? = null
        var cameraState: androidx.lifecycle.LiveData<CameraState>? = null
        var lastCode: String? = null
        var lastEmission = 0L
        val observer = Observer<CameraState> { state ->
            if (active.get() && state.error != null) currentOnError("Камера недоступна. Введите код вручную.")
        }
        analysis.setAnalyzer(executor) { frame ->
            val mediaImage = frame.image
            if (!active.get() || delivered.get() || mediaImage == null) {
                frame.close()
            } else {
                try {
                    val image = InputImage.fromMediaImage(mediaImage, frame.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener(main) { codes ->
                            if (active.get() && !delivered.get()) {
                                val code = codes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                                val now = SystemClock.elapsedRealtime()
                                if (code != null && (code != lastCode || now - lastEmission > 1_500)) {
                                    lastCode = code
                                    lastEmission = now
                                    if (currentOnBarcode(code)) delivered.set(true)
                                }
                            }
                        }
                        .addOnFailureListener(main) {
                            if (active.get()) currentOnError("Не удалось распознать код. Введите его вручную.")
                        }
                        .addOnCompleteListener { frame.close() }
                } catch (_: Exception) {
                    frame.close()
                    main.execute { if (active.get()) currentOnError("Сканер недоступен. Введите код вручную.") }
                }
            }
        }
        future.addListener({
            if (active.get()) {
                try {
                    provider = future.get()
                    val rotation = view.display?.rotation ?: AndroidSurface.ROTATION_0
                    preview.targetRotation = rotation
                    analysis.targetRotation = rotation
                    val availableProvider = checkNotNull(provider)
                    val selector = if (availableProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else CameraSelector.DEFAULT_FRONT_CAMERA
                    val camera = availableProvider.bindToLifecycle(owner, selector, preview, analysis)
                    cameraState = camera.cameraInfo.cameraState
                    cameraState?.observe(owner, observer)
                } catch (_: Exception) {
                    currentOnError("Не удалось открыть камеру. Введите код вручную.")
                }
            }
        }, main)
        onDispose {
            active.set(false)
            analysis.clearAnalyzer()
            cameraState?.removeObserver(observer)
            try {
                provider?.unbind(preview, analysis)
            } finally {
                try { scanner.close() } finally { executor.shutdown() }
            }
        }
    }
}
