import com.sun.jna.*
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.*
import java.io.*

fun getPipePrefix() = if (Platform.isWindows()) "\\\\.\\pipe\\" else "/tmp/"

fun createInputPipe(name: String): InputStream {
    return if (Platform.isWindows()) {
        Win32PipeInputStream(createWin32Pipe(name, true))
    } else {
        createLinuxPipe(name).inputStream()
    }
}

fun createOutputPipe(name: String): OutputStream {
    return if (Platform.isWindows()) {
        Win32PipeOutputStream(createWin32Pipe(name, false))
    } else {
        createLinuxPipe(name).outputStream()
    }
}

fun cleanupPipeOnCancel(name: String) {
    if (!Platform.isWindows()) {
        File(getPipePrefix() + name).delete()
        createLinuxPipe(name)
    }
}

fun createLinuxPipe(name: String): File {
    val fullName = getPipePrefix() + name
    val file = File(fullName)
    if (!file.exists()) {
        Runtime.getRuntime().exec("mkfifo $fullName")
    }
    return file
}

fun createWin32Pipe(name: String, isIn: Boolean): WinNT.HANDLE {
    val pipeName = getPipePrefix() + name
    val dir = if (isIn) WinBase.PIPE_ACCESS_INBOUND else WinBase.PIPE_ACCESS_OUTBOUND
    val hNamedPipe = Kernel32.INSTANCE.CreateNamedPipe(
        pipeName,
        dir,
        WinBase.PIPE_TYPE_BYTE or WinBase.PIPE_READMODE_BYTE or WinBase.PIPE_WAIT,  // dwPipeMode
        1, Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt(),  // nInBufferSize,
        1000000,
        null
    ) ?: error("CreateNamedPipe error")
    Kernel32.INSTANCE.ConnectNamedPipe(hNamedPipe, null) || error("ConnectNamedPipe")
    return hNamedPipe
}

class Win32PipeInputStream(private val pipe: WinNT.HANDLE) : InputStream() {
    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b) == -1) -1 else 0xFF and b[0].toInt()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val localBuffer = if (off == 0 && b.size == len) b else ByteArray(len)
        val x = IntByReference()
        val res = Kernel32.INSTANCE.ReadFile(pipe, localBuffer, len, x, null)
        if (!res) {
            val readError = Kernel32.INSTANCE.GetLastError()
            if (readError == Kernel32.ERROR_BROKEN_PIPE) return -1
            throw IOException("Can't read in PipeInputStream")
        }
        if (x.value == 0) return -1
        if (localBuffer !== b) System.arraycopy(localBuffer, 0, b, off, x.value)
        return x.value
    }

    override fun close() {
        Kernel32.INSTANCE.CloseHandle(pipe)
    }
}

class Win32PipeOutputStream(private val pipe: WinNT.HANDLE) : OutputStream() {
    override fun write(b: Int) {
        write(byteArrayOf((0xFF and b).toByte()))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val toWrite = if (off == 0 && len == b.size) b else b.slice(off until off + len).toByteArray()
        val x = IntByReference()
        Kernel32.INSTANCE.WriteFile(pipe, toWrite, len, x, null)
        if (x.value != len) throw IOException("Can't write in PipeOutputStream ${x.value} $len")
    }

    override fun close() {
        Kernel32.INSTANCE.CloseHandle(pipe)
    }
}
