/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
 *
 * Copyright (c) 2024, joelhalen <andy@joelhalen.net>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.droptracker.video;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Video quality presets for recording.
 * Adapted from the osrs-tracker-plugin by Dennis De Vulder (BSD 2-Clause).
 * Frames are captured as JPEG and uploaded to the server for processing.
 */
@Getter
@RequiredArgsConstructor
public enum VideoQuality
{
	/**
	 * Screenshot only - no video recording. This is the default.
	 */
	SCREENSHOT_ONLY(
		"Screenshot Only",
		0,     // No video
		0,     // No FPS
		0.0f   // No quality
	),

	/**
	 * Video recording - 10 seconds at 20 FPS, 80% JPEG quality, 1080p.
	 * Performance-oriented default to minimize CPU overhead.
	 */
	VIDEO(
		"Video (20 FPS)",
		10000, // 10 seconds
		20,    // 20 FPS
		0.80f  // 80% JPEG quality
	);

	private final String displayName;
	private final int durationMs;
	private final int fps;
	private final float jpegQuality;

	/**
	 * Checks if this quality preset requires video recording.
	 *
	 * @return true if this preset records video frames
	 */
	public boolean requiresVideo()
	{
		return this != SCREENSHOT_ONLY;
	}

	/**
	 * Gets the default quality preset.
	 *
	 * @return SCREENSHOT_ONLY as the default
	 */
	public static VideoQuality getDefault()
	{
		return SCREENSHOT_ONLY;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
