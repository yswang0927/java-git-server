package com.gdk.git;

import java.io.Serializable;

/**
 * GitNote is a serializable model class that represents a git note. This class
 * retains an instance of the RefModel which contains the commit in which this
 * git note was created.
 *
 * @author James Moger
 */
public class GitNote implements Serializable {
	private static final long serialVersionUID = -6827161045292803794L;

	public final String content;
	public final RefModel notesRef;

	public GitNote(RefModel notesRef, String text) {
		this.notesRef = notesRef;
		this.content = text;
	}
}