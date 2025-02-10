package com.gdk.git;

import java.io.ByteArrayOutputStream;

/**
 * A {@link ByteArrayOutputStream} that can be reset to a specified position.
 *
 * @author Tom
 */
public class ResettableByteArrayOutputStream extends ByteArrayOutputStream {
	/**
	 * Reset the stream to the given position. If {@code mark} is <= 0, see {@link #reset()}.
	 * A no-op if the stream contains less than {@code mark} bytes. Otherwise, resets the
	 * current writing position to {@code mark}. Previously allocated buffer space will be
	 * reused in subsequent writes.
	 *
	 * @param mark to set the current writing position to.
	 */
	public synchronized void resetTo(int mark) {
		if (mark <= 0) {
			reset();
		} else if (mark < count) {
			count = mark;
		}
	}

}
