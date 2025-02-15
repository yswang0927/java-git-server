package com.gdk.git;

import org.eclipse.jgit.events.RefsChangedEvent;
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * The event fired by other classes to allow this service to index tickets.
 *
 * @author James Moger
 */
public class ReceiveCommandEvent extends RefsChangedEvent {
	public final RepositoryModel model;
	public final ReceiveCommand cmd;

	public ReceiveCommandEvent(RepositoryModel model, ReceiveCommand cmd) {
		this.model = model;
		this.cmd = cmd;
	}
}