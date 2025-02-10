package com.gdk.git;

import java.io.Serializable;
import java.util.Date;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * AnnotatedLine is a serializable model class that represents a the most recent
 * author, date, and commit id of a line in a source file.
 *
 * @author James Moger
 */
public class AnnotatedLine implements Serializable {
    private static final long serialVersionUID = -4867802757130207286L;

    public final String commitId;
    public final String author;
    public final Date when;
    public final int lineNumber;
    public final String data;

    public AnnotatedLine(RevCommit commit, int lineNumber, String data) {
        if (commit == null) {
            this.commitId = ObjectId.zeroId().getName();
            this.author = "?";
            this.when = new Date(0);
        } else {
            this.commitId = commit.getName();
            this.author = commit.getAuthorIdent().getName();
            this.when = commit.getAuthorIdent().getWhen();
        }
        this.lineNumber = lineNumber;
        this.data = data;
    }
}