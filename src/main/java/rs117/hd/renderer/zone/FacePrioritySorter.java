/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
package rs117.hd.renderer.zone;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.PrimitiveIntArray;

import static rs117.hd.renderer.zone.Zone.VERT_SIZE;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class FacePrioritySorter implements AutoCloseable {
	public static ConcurrentPool<FacePrioritySorter> POOL;

	public static final int MAX_FACE_COUNT = 8192;
	static final int MAX_DIAMETER = 6000;
	static final int MAX_FACES_PER_PRIORITY = 4000;
	private static final int PRIORITY_COUNT = 12;

	public int[] faceDistances = new int[MAX_FACE_COUNT];

	private int priorityFaceCapacity = MAX_FACES_PER_PRIORITY;
	private int[] orderedFaces = new int[PRIORITY_COUNT * MAX_FACES_PER_PRIORITY];
	private final int[] numOfPriority = new int[PRIORITY_COUNT];
	private int[] eq10 = new int[MAX_FACES_PER_PRIORITY];
	private int[] eq11 = new int[MAX_FACES_PER_PRIORITY];
	private final int[] lt10 = new int[PRIORITY_COUNT];

	private final int[] zsortHead = new int[MAX_DIAMETER];
	private final int[] zsortTail = new int[MAX_DIAMETER];
	private int[] zsortNext = new int[MAX_FACE_COUNT];

	void ensureCapacity(int faceCount) {
		if (faceDistances.length < faceCount)
			faceDistances = Arrays.copyOf(faceDistances, faceCount);
		if (zsortNext.length < faceCount)
			zsortNext = Arrays.copyOf(zsortNext, faceCount);
		if (priorityFaceCapacity < faceCount) {
			priorityFaceCapacity = faceCount;
			orderedFaces = new int[PRIORITY_COUNT * priorityFaceCapacity];
			eq10 = new int[priorityFaceCapacity];
			eq11 = new int[priorityFaceCapacity];
		}
	}

	void sortModelFaces(PrimitiveIntArray visibleFaces, Model model) {
		ensureCapacity(model.getFaceCount());

		final int diameter = model.getDiameter();
		if (diameter <= 0 || diameter >= MAX_DIAMETER) {
			ModelRenderDiagnostics.captureWarning(
				"modelFaceSort.invalidDiameter",
				"Skipping model face sort due to invalid diameter",
				ModelRenderDiagnostics.context("model-face-sort")
					.model(model)
					.scratchLimits()
					.extra("sortDiameter", diameter)
			);
			return;
		}

		int unsortedCount = 0;
		int minFz = diameter, maxFz = 0;
		boolean needsClear = true;

		// Build the z-sorted linked list of faces
		for (int i = 0; i < visibleFaces.length; ++i) {
			final int faceIdx = visibleFaces.array[i];
			if (faceDistances[faceIdx] == Integer.MIN_VALUE) {
				orderedFaces[unsortedCount++] = faceIdx;
				continue;
			}

			if (needsClear) {
				Arrays.fill(zsortHead, 0, diameter + 1, -1);
				Arrays.fill(zsortTail, 0, diameter + 1, -1);
				needsClear = false;
			}

			final int distance = clamp(faceDistances[faceIdx], 0, diameter);
			final int tailFaceIdx = zsortTail[distance];
			if (tailFaceIdx == -1) {
				zsortHead[distance] = zsortTail[distance] = faceIdx;
				zsortNext[faceIdx] = -1;

				minFz = min(minFz, distance);
				maxFz = max(maxFz, distance);
			} else {
				zsortNext[tailFaceIdx] = faceIdx;
				zsortNext[faceIdx] = -1;
				zsortTail[distance] = faceIdx;
			}
		}

		if (visibleFaces.length - unsortedCount == 0)
			return; // No faces to sort, so don't modify the visible faces array

		visibleFaces.reset();
		if (unsortedCount > 0) // Push unsorted faces to be drawn first
			visibleFaces.put(orderedFaces, 0, unsortedCount);

		final byte[] priorities = model.getFaceRenderPriorities();
		if (priorities == null) {
			for (int i = maxFz; i >= minFz; --i) {
				for (int f = zsortHead[i]; f != -1; f = zsortNext[f])
					visibleFaces.put(f);
			}
			return;
		}

		Arrays.fill(numOfPriority, 0);
		Arrays.fill(lt10, 0);

		int invalidPriorityCount = 0;
		int firstInvalidPriority = 0;
		int firstInvalidPriorityFace = -1;
		for (int i = maxFz; i >= minFz; --i) {
			for (int f = zsortHead[i]; f != -1; f = zsortNext[f]) {
				final int pri = priorities[f];
				if (pri < 0 || pri >= PRIORITY_COUNT) {
					if (invalidPriorityCount++ == 0) {
						firstInvalidPriority = pri;
						firstInvalidPriorityFace = f;
					}
					visibleFaces.put(f);
					continue;
				}

				final int idx = numOfPriority[pri]++;

				orderedFaces[pri * priorityFaceCapacity + idx] = f;

				if (pri < 10)
					lt10[pri] += i;
				else if (pri == 10)
					eq10[idx] = i;
				else
					eq11[idx] = i;
			}
		}
		if (invalidPriorityCount > 0) {
			ModelRenderDiagnostics.captureWarning(
				"modelFaceSort.invalidPriority",
				"Skipping invalid model face priorities",
				ModelRenderDiagnostics.context("model-face-priority-sort")
					.model(model)
					.scratchLimits()
					.extra("invalidPriorityCount", invalidPriorityCount)
					.extra("firstInvalidPriority", firstInvalidPriority)
					.extra("firstInvalidPriorityFace", firstInvalidPriorityFace)
			);
		}

		int avg12 = (numOfPriority[1] + numOfPriority[2]) > 0 ?
			(lt10[1] + lt10[2]) / (numOfPriority[1] + numOfPriority[2]) : 0;

		int avg34 = (numOfPriority[3] + numOfPriority[4]) > 0 ?
			(lt10[3] + lt10[4]) / (numOfPriority[3] + numOfPriority[4]) : 0;

		int avg68 = (numOfPriority[6] + numOfPriority[8]) > 0 ?
			(lt10[6] + lt10[8]) / (numOfPriority[6] + numOfPriority[8]) : 0;

		int drawnFaces = 0;
		int numDynFaces = numOfPriority[10];
		int dynBase = 10 * priorityFaceCapacity;
		int[] dynDist = eq10;

		if (numDynFaces == 0) {
			numDynFaces = numOfPriority[11];
			dynBase = 11 * priorityFaceCapacity;
			dynDist = eq11;
		}

		int currFaceDistance = drawnFaces < numDynFaces ? dynDist[drawnFaces] : -1000;

		for (int pri = 0; pri < 10; ++pri) {
			while (
				pri == 0 && currFaceDistance > avg12 ||
				pri == 3 && currFaceDistance > avg34 ||
				pri == 5 && currFaceDistance > avg68
			) {
				visibleFaces.put(orderedFaces[dynBase + drawnFaces++]);

				if (drawnFaces == numDynFaces && dynBase == 10 * priorityFaceCapacity) {
					drawnFaces = 0;
					numDynFaces = numOfPriority[11];
					dynBase = 11 * priorityFaceCapacity;
					dynDist = eq11;
				}

				currFaceDistance = drawnFaces < numDynFaces ? dynDist[drawnFaces] : -1000;
			}

			visibleFaces.put(
				orderedFaces,
				pri * priorityFaceCapacity,
				numOfPriority[pri]
			);
		}

		while (currFaceDistance != -1000) {
			visibleFaces.put(orderedFaces[dynBase + drawnFaces++]);

			if (drawnFaces == numDynFaces && dynBase == 10 * priorityFaceCapacity) {
				drawnFaces = 0;
				numDynFaces = numOfPriority[11];
				dynBase = 11 * priorityFaceCapacity;
				dynDist = eq11;
			}

			currFaceDistance = drawnFaces < numDynFaces ? dynDist[drawnFaces] : -1000;
		}
	}

	void sortStaticModelFacesByDistance(
		Zone.AlphaModel m,
		int yawCos, int yawSin,
		int pitchCos, int pitchSin
	) {
		final int radius = m.radius;
		final int diameter = 1 + radius * 2;
		if (diameter <= 0 || diameter >= MAX_DIAMETER) {
			ModelRenderDiagnostics.captureWarning(
				"staticAlphaSort.invalidDiameter",
				"Skipping static alpha model sort due to invalid diameter",
				ModelRenderDiagnostics.context("static-alpha-sort")
					.alphaModel(m)
					.scratchLimits()
					.extra("sortDiameter", diameter)
			);
			return;
		}

		final int faceCount = m.packedFaces.length;
		ensureCapacity(faceCount);

		Arrays.fill(zsortHead, 0, diameter, -1);
		Arrays.fill(zsortTail, 0, diameter, -1);

		int minFz = diameter, maxFz = 0;
		int invalidDistanceCount = 0;
		int firstInvalidDistance = 0;
		int firstInvalidDistanceFace = -1;
		for (int i = 0; i < faceCount; ++i) {
			final int packed = m.packedFaces[i];
			final int x = packed >> 21;
			final int y = (packed << 11) >> 22;
			final int z = (packed << 21) >> 21;

			int fz = ((z * yawCos - x * yawSin) >> 16);
			fz = ((y * pitchSin + fz * pitchCos) >> 16) + radius;
			if (fz < 0 || fz >= diameter) {
				if (invalidDistanceCount++ == 0) {
					firstInvalidDistance = fz;
					firstInvalidDistanceFace = i;
				}
				continue;
			}

			if (zsortTail[fz] == -1) {
				zsortHead[fz] = zsortTail[fz] = i;
				zsortNext[i] = -1;

				minFz = min(minFz, fz);
				maxFz = max(maxFz, fz);
			} else {
				int lastFace = zsortTail[fz];
				zsortNext[lastFace] = i;
				zsortNext[i] = -1;
				zsortTail[fz] = i;
			}
		}
		if (invalidDistanceCount > 0) {
			ModelRenderDiagnostics.captureWarning(
				"staticAlphaSort.invalidDistance",
				"Skipping static alpha faces with invalid sort distance",
				ModelRenderDiagnostics.context("static-alpha-sort")
					.alphaModel(m)
					.scratchLimits()
					.extra("invalidDistanceCount", invalidDistanceCount)
					.extra("firstInvalidDistance", firstInvalidDistance)
					.extra("firstInvalidDistanceFace", firstInvalidDistanceFace)
					.extra("sortDiameter", diameter)
			);
		}

		final int start = m.startpos / (VERT_SIZE >> 2);
		for (int i = maxFz; i >= minFz; --i) {
			for (int f = zsortHead[i]; f != -1; f = zsortNext[f]) {
				if (m.sortedFacesLen + 3 > m.sortedFaces.length) {
					ModelRenderDiagnostics.captureWarning(
						"staticAlphaSort.sortedFaceCapacity",
						"Skipping remaining static alpha faces because sorted face scratch is full",
						ModelRenderDiagnostics.context("static-alpha-sort")
							.alphaModel(m)
							.scratchLimits()
							.extra("sortedFacesLen", m.sortedFacesLen)
							.extra("sortedFaceCapacity", m.sortedFaces.length)
					);
					return;
				}

				if (f >= faceCount)
					continue;

				final int sortedOffset = m.sortedFacesLen;
				final int faceStart = f * 3 + start;
				m.sortedFaces[sortedOffset] = faceStart;
				m.sortedFaces[sortedOffset + 1] = faceStart + 1;
				m.sortedFaces[sortedOffset + 2] = faceStart + 2;
				m.sortedFacesLen += 3;
			}
		}
	}

	@Override
	public void close() {
		POOL.recycle(this);
	}
}
