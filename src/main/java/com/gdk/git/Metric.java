package com.gdk.git;

import java.io.Serializable;

/**
 * Metric is a serializable model class that encapsulates metrics for some given
 * type.
 *
 * @author James Moger
 */
public class Metric implements Serializable, Comparable<Metric> {
	private static final long serialVersionUID = -263704219762559994L;

	public final String name;
	public double count;
	public double tag;
	public int duration;

	public Metric(String name) {
		this.name = name;
	}

	@Override
	public int compareTo(Metric o) {
		if (count > o.count) {
			return -1;
		}
		if (count < o.count) {
			return 1;
		}
		return 0;
	}

}