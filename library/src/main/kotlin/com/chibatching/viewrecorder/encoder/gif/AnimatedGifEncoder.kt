package com.chibatching.viewrecorder.encoder.gif

import java.io.IOException
import java.io.OutputStream

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log

public class AnimatedGifEncoder (val out : OutputStream) {

    var width: Int = 0 // image size

    var height: Int = 0

    var x: Int = 0

    var y: Int = 0

    var transparent: Int = -1 // transparent color if given

    var transIndex: Int = 0 // transparent index in color table

    var repeat: Int = -1 // no repeat
        /**
         * Sets the number of times the set of GIF frames should be played. Default is
         * 1; 0 means play indefinitely. Must be invoked before the first image is
         * added.
         * @param iter
         *          int number of iterations.
         */
        set(iter) { if (iter >= 0) $repeat = iter }

    var delay: Int = 0 // frame delay (hundredths)
        /**
         * Sets the delay time between each frame, or changes it for subsequent frames
         * (applies to last frame added).
         * @param ms
         *          int delay time in milliseconds
         */
        set(ms) { $delay = ms /10 }

    var started: Boolean = false // ready to output frames

    var colorDepth: Int = 0 // number of bit planes

    var usedEntry: BooleanArray = BooleanArray(256) // active palette entries

    var palSize: Int = 7 // color table size (bits-1)

    var dispose: Int = -1 // disposal code (-1 = use default)
        /**
         * Sets the GIF frame disposal code for the last added frame and any
         * subsequent frames. Default is 0 if no transparent color has been set,
         * otherwise 2.
         * @param code
         *          int disposal code.
         */
        set(code) { if (code >= 0) $dispose = code }

    var closeStream: Boolean = false // close stream when finished

    var firstFrame: Boolean = true

    var sizeSet: Boolean = false // if false, get size from first frame

    var sample: Int = 10 // default sample interval for quantizer

    data class AnalyzedData(val indexedPixels: ByteArray, val colorTab: IntArray)

    /**
     * Adds next GIF frame. The frame is not written immediately, but is actually
     * deferred until the next frame is received so that timing data can be
     * inserted. Invoking `finish()` flushes all frames. If
     * `setSize` was not invoked, the size of the first image is used
     * for all subsequent frames.

     * @param im
     * *          BufferedImage containing frame to write.
     * *
     * @return true if successful.
     */
    public fun addFrame(im: Bitmap): Boolean {
        if (!started) {
            throw IllegalStateException("Encoder should had run start().")
        }
        var ok = true
        try {
            if (!sizeSet) {
                // use first frame's size
                setSize(im.getWidth(), im.getHeight())
            }
            val pixels = getImagePixels(im) // convert to correct format if necessary
            val analyzedData = analyzePixels(pixels) // build color table & map pixels
            if (firstFrame) {
                writeLSD() // logical screen descriptior
                writePalette(analyzedData.colorTab) // global color table
                if (repeat >= 0) {
                    // use NS app extension to indicate reps
                    writeNetscapeExt()
                }
            }
            writeGraphicCtrlExt() // write graphic control extension
            writeImageDesc() // image descriptor
            if (!firstFrame) {
                writePalette(analyzedData.colorTab) // local color table
            }
            writePixels(analyzedData.indexedPixels) // encode and write pixel data
            firstFrame = false
        } catch (e: IOException) {
            ok = false
        }

        return ok
    }

    /**
     * Flushes any pending data and closes output file. If writing to an
     * OutputStream, the stream is not closed.
     */
    public fun finish(): Boolean {
        if (!started)
            return false
        var ok = true
        started = false
        try {
            out.write(59) // gif trailer
            out.flush()
            if (closeStream) {
                out.close()
            }
        } catch (e: IOException) {
            ok = false
        }

        // reset for subsequent use
        transIndex = 0
        closeStream = false
        firstFrame = true

        return ok
    }

    /**
     * Sets frame rate in frames per second.
     * @param fps
     * *          float frame rate (frames per second)
     */
    public fun setFrameRate(fps: Float) {
        if (fps != 0.toFloat()) {
            delay = (1000 / fps).toInt()
        }
    }

    /**
     * Sets quality of color quantization (conversion of images to the maximum 256
     * colors allowed by the GIF specification). Lower values (minimum = 1)
     * produce better colors, but slow processing significantly. 10 is the
     * default, and produces good color mapping at reasonable speeds. Values
     * greater than 20 do not yield significant improvements in speed.

     * @param quality
     * *          int greater than 0.
     * *
     * @return
     */
    public fun setQuality(quality: Int) {
        if (quality < 1) {
            sample = 1
        } else {
            sample = quality
        }
    }

    /**
     * Sets the GIF frame size. The default size is the size of the first frame
     * added if this method is not invoked.

     * @param w
     * *          int frame width.
     * *
     * @param h
     * *          int frame height.
     */
    public fun setSize(w: Int, h: Int) {
        width = w
        height = h
        if (width < 1)
            width = 320
        if (height < 1)
            height = 240
        sizeSet = true
    }

    /**
     * Sets the GIF frame position. The position is 0,0 by default.
     * Useful for only updating a section of the image

     * @param x
     * *          int frame x position.
     * *
     * @param y
     * *          int frame y position.
     */
    public fun setPosition(x: Int, y: Int) {
        this.x = x
        this.y = y
    }

    /**
     * Initiates GIF file creation on the given stream. The stream is not closed
     * automatically.

     * @param os
     * *          OutputStream on which GIF images are written.
     * *
     * @return false if initial write failed.
     */
    public fun start(): Boolean {
        var ok = true
        closeStream = false
        try {
            writeString("GIF89a") // header
        } catch (e: IOException) {
            ok = false
        }
        started = ok
        return started
    }

    /**
     * Analyzes image colors and creates color map.
     */
    protected fun analyzePixels(pixels: IntArray) : AnalyzedData {
        val len = pixels.size()
        val nPix = len / 3
        val indexedPixels = ByteArray(nPix)
        val nq = NeuQuant(pixels, len, sample)
        // initialize quantizer
        val colorTab = nq.process() // create reduced palette
        // convert map from BGR to RGB
        for (i in 0..colorTab.size() / 3 - 1) {
            val tind = i * 3
            val temp = colorTab[tind]
            colorTab[tind] = colorTab[tind + 2]
            colorTab[tind + 2] = temp
            usedEntry[i] = false
        }

        // map image pixels to new palette
        var k = 0
        for (i in 0..nPix - 1) {
            val index = nq.map(pixels[k++] and 255, pixels[k++] and 255, pixels[k++] and 255)
            usedEntry.set(index, true)
            indexedPixels.set(i, index.toByte())
        }

        colorDepth = 8
        palSize = 7
        // get closest match to transparent color if specified
        if (transparent != -1) {
            transIndex = findClosest(transparent, colorTab)
        }

        return AnalyzedData(indexedPixels, colorTab)
    }

    /**
     * Returns index of palette color closest to c

     */
    protected fun findClosest(c: Int, colorTab: IntArray): Int {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = (c shr 0) and 255
        var minpos = 0
        var dmin = 256 * 256 * 256
        val len = colorTab.size()
        run {
            var i = 0
            while (i < len) {
                val dr = r - (colorTab[i++] and 255)
                val dg = g - (colorTab[i++] and 255)
                val db = b - (colorTab[i] and 255)
                val d = dr * dr + dg * dg + db * db
                val index = i / 3
                if (usedEntry[index] && (d < dmin)) {
                    dmin = d
                    minpos = index
                }
                i++
            }
        }
        return minpos
    }

    /**
     * Extracts image pixels into byte array "pixels"
     */
    protected fun getImagePixels(image: Bitmap): IntArray {
        val w = image.getWidth()
        val h = image.getHeight()
        var temp: Bitmap? = null
        if ((w != width) || (h != height)) {
            // create new image with right size/format
            temp = Bitmap.createBitmap(width, height, Config.RGB_565)
            val g = Canvas(temp!!)
            g.drawBitmap(image, 0.toFloat(), 0.toFloat(), Paint())
        }
        val data = getImageData(if (temp == null) image else temp as Bitmap)
        val pixels = IntArray(data.size() * 3)
        for (i in 0..data.size() - 1) {
            val tind = i * 3
            pixels[tind] = (data[i] shr 0) and 255
            pixels[tind + 1] = (data[i] shr 8) and 255
            pixels[tind + 2] = (data[i] shr 16) and 255
        }
        return pixels
    }

    protected fun getImageData(img: Bitmap): IntArray {
        val w = img.getWidth()
        val h = img.getHeight()

        val data = IntArray(w * h)
        img.getPixels(data, 0, w, 0, 0, w, h)
        return data
    }

    /**
     * Writes Graphic Control Extension
     */
    throws(javaClass<IOException>())
    protected fun writeGraphicCtrlExt() {
        out.write(33) // extension introducer
        out.write(249) // GCE label
        out.write(4) // data block size
        val transp: Int
        var disp: Int
        if (transparent == -1) {
            transp = 0
            disp = 0 // dispose = no action
        } else {
            transp = 1
            disp = 2 // force clear if using transparent color
        }
        if (dispose >= 0) {
            disp = dispose and 7 // user override
        }
        disp = disp shl 2

        // packed fields
        out.write(0 or // 1:3 reserved
                disp or // 4:6 disposal
                0 or // 7 user input - 0 = none
                transp) // 8 transparency flag

        writeShort(delay) // delay x 1/100 sec
        out.write(transIndex) // transparent color index
        out.write(0) // block terminator
    }

    /**
     * Writes Image Descriptor
     */
    throws(javaClass<IOException>())
    protected fun writeImageDesc() {
        out.write(44) // image separator
        writeShort(x) // image position x,y = 0,0
        writeShort(y)
        writeShort(width) // image size
        writeShort(height)
        // packed fields
        if (firstFrame) {
            // no LCT - GCT is used for first (or only) frame
            out.write(0)
        } else {
            // specify normal LCT
            out.write(128 or // 1 local color table 1=yes
                    0 or // 2 interlace - 0=no
                    0 or // 3 sorted - 0=no
                    0 or // 4-5 reserved
                    palSize) // 6-8 size of color table
        }
    }

    /**
     * Writes Logical Screen Descriptor
     */
    throws(javaClass<IOException>())
    protected fun writeLSD() {
        // logical screen size
        writeShort(width)
        writeShort(height)
        // packed fields
        out.write((128 or // 1 : global color table flag = 1 (gct used)
                112 or // 2-4 : color resolution = 7
                0 or // 5 : gct sort flag = 0
                palSize)) // 6-8 : gct size

        out.write(0) // background color index
        out.write(0) // pixel aspect ratio - assume 1:1
    }

    /**
     * Writes Netscape application extension to define repeat count.
     */
    throws(javaClass<IOException>())
    protected fun writeNetscapeExt() {
        out.write(33) // extension introducer
        out.write(255) // app extension label
        out.write(11) // block size
        writeString("NETSCAPE" + "2.0") // app id + auth code
        out.write(3) // sub-block size
        out.write(1) // loop sub-block id
        writeShort(repeat) // loop count (extra iterations, 0=repeat forever)
        out.write(0) // block terminator
    }

    /**
     * Writes color table
     */
    throws(javaClass<IOException>())
    protected fun writePalette(colorTab: IntArray) {
        for (i in 0..colorTab.size() - 1) {
            out.write(colorTab[i])
        }
        val n = (3 * 256) - colorTab.size()
        for (i in 0..n - 1) {
            out.write(0)
        }
    }

    /**
     * Encodes and writes pixel data
     */
    throws(javaClass<IOException>())
    protected fun writePixels(indexedPixels: ByteArray) {
        val encoder = LZWEncoder(width, height, indexedPixels, colorDepth)
        encoder.encode(out)
    }

    /**
     * Write 16-bit value to output stream, LSB first
     */
    throws(javaClass<IOException>())
    protected fun writeShort(value: Int) {
        out.write(value and 255)
        out.write((value shr 8) and 255)
    }

    /**
     * Writes string to output stream
     */
    throws(javaClass<IOException>())
    fun writeString(s: String) {
        out.write(s.toByteArray())
    }
}
