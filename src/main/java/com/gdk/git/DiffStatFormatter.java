package com.gdk.git;

import java.io.IOException;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.io.NullOutputStream;

import com.gdk.git.PathModel.PathChangeModel;
import com.gdk.git.DiffUtils.DiffStat;

/**
 * Calculates a DiffStat.
 *
 * @author James Moger
 */
public class DiffStatFormatter extends DiffFormatter {

    private final DiffStat diffStat;

    private PathChangeModel path;

    public DiffStatFormatter(String commitId, Repository repository) {
        super(NullOutputStream.INSTANCE);
        diffStat = new DiffStat(commitId, repository);
    }

    @Override
    public void format(DiffEntry entry) throws IOException {
        path = diffStat.addPath(entry);
        super.format(entry);
    }

    @Override
    protected void writeLine(final char prefix, final RawText text, final int cur) throws IOException {
        path.update(prefix);
    }

    public DiffStat getDiffStat() {
        return diffStat;
    }
}
