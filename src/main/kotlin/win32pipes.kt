import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.IntByReference
import java.io.*

fun createPipeIn(name: String): InputStream {
	return PipeInputStream(createPipe(name, true))
}

fun createPipeOut(name: String): OutputStream {
	return PipeOutputStream(createPipe(name, false))
}

fun createPipe(name: String, isIn: Boolean): WinNT.HANDLE {
	val pipeName = "\\\\.\\pipe\\$name"
	val dir = if (isIn) WinBase.PIPE_ACCESS_INBOUND else WinBase.PIPE_ACCESS_OUTBOUND
	val hNamedPipe = Kernel32.INSTANCE.CreateNamedPipe(
		pipeName,
		dir,  // dwOpenMode
		WinBase.PIPE_TYPE_BYTE or WinBase.PIPE_READMODE_BYTE or WinBase.PIPE_WAIT,  // dwPipeMode
		1, Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt(),  // nInBufferSize,
		1000000,  // nDefaultTimeOut,
		null
	) ?: error("null") // lpSecurityAttributes
	Kernel32.INSTANCE.ConnectNamedPipe(hNamedPipe, null)
	assertCallSucceeded("CreateNamedPipe", WinBase.INVALID_HANDLE_VALUE != hNamedPipe)
	return hNamedPipe
}

class PipeInputStream(private val pipe: WinNT.HANDLE) : InputStream() {
	override fun read(): Int {
		val b = ByteArray(1)
		val read: Int = read(b)
		return if (read == -1) -1 else 0xFF and b[0].toInt()
	}

	override fun read(b: ByteArray?, off: Int, len: Int): Int {
		b!!
		val localBuffer = if (off == 0 && b.size == len) b else ByteArray(len)
		val x = IntByReference()
		val res = Kernel32.INSTANCE.ReadFile(pipe, localBuffer, len, x, null)
		if (!res) throw IOException("Can't read in PipeInputStream")
		if (x.value == 0) return -1
		if (localBuffer !== b) System.arraycopy(localBuffer, 0, b, off, x.value)
		return x.value
	}

	override fun close() {
		Kernel32.INSTANCE.CloseHandle(pipe)
	}
}

class PipeOutputStream(private val pipe: WinNT.HANDLE) : OutputStream() {
	override fun write(b: Int) {
		write(byteArrayOf((0xFF and b).toByte()))
	}

	override fun write(b: ByteArray?, off: Int, len: Int) {
		b!!
		val toWrite = if (off == 0 && len == b.size) b else b.slice(off until off + len).toByteArray()
		val x = IntByReference()
		Kernel32.INSTANCE.WriteFile(pipe, toWrite, len, x, null)
		if (x.value != len) throw IOException("Can't write in PipeOutputStream ${x.value} $len")
	}

	override fun close() {
		Kernel32.INSTANCE.CloseHandle(pipe)
	}
}

fun assertCallSucceeded(message: String, result: Boolean) {
	if (result) return
	val hr = Kernel32.INSTANCE.GetLastError()
	if (hr == WinError.ERROR_SUCCESS) {
		error("$message failed with unknown reason code")
	} else {
		error(message + " failed: hr=" + hr + " - 0x" + Integer.toHexString(hr))
	}
}
