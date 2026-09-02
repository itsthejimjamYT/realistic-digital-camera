package com.itsthejimjam.realcamera;

/**
 * Effect of a physical filter. ND cuts light (in stops); polarizer and mist are 0..1
 * strengths applied in the finishing pass. A given filter item sets exactly one of these.
 */
public record FilterSpec(float ndStops, float polarizer, float mist) {

	public static final FilterSpec NONE = new FilterSpec(0, 0, 0);

	public boolean isEmpty() {
		return ndStops == 0 && polarizer == 0 && mist == 0;
	}
}
