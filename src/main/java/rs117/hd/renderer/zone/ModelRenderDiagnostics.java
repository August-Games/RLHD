package rs117.hd.renderer.zone;

import io.sentry.IScope;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ModelHash;

@Slf4j
final class ModelRenderDiagnostics {
	private static final long REPORT_INTERVAL_MILLIS = 60_000L;
	private static final ConcurrentHashMap<String, RateLimitState> STATES = new ConcurrentHashMap<>();

	private ModelRenderDiagnostics() {}

	static Context context(String renderPath) {
		return new Context(renderPath);
	}

	static void captureWarning(String key, String message, Context context) {
		capture(key, message, SentryLevel.WARNING, context, null);
	}

	static void captureError(String key, String message, Context context, Throwable throwable) {
		capture(key, message, SentryLevel.ERROR, context, throwable);
	}

	private static void capture(
		String key,
		String message,
		SentryLevel level,
		Context context,
		@Nullable Throwable throwable
	) {
		RateLimitSnapshot snapshot = STATES.computeIfAbsent(key, ignored -> new RateLimitState())
			.record(System.currentTimeMillis(), context);
		if (snapshot == null)
			return;

		Context eventContext = context.copy()
			.extra("diagnosticKey", key)
			.extra("diagnosticMessage", message)
			.extra("suppressedLogsSinceLast", snapshot.suppressed)
			.extra("suppressedMaxVertices", snapshot.maxVertices)
			.extra("suppressedMaxFaces", snapshot.maxFaces)
			.extra("suppressedMaxDiameter", snapshot.maxDiameter);

		try {
			if (throwable != null) {
				Sentry.captureException(throwable, scope -> applyScope(scope, key, message, level, eventContext));
			} else {
				Sentry.captureMessage(message, level, scope -> applyScope(scope, key, message, level, eventContext));
			}
		} catch (Throwable sentryFailure) {
			log.debug("Unable to capture 117 HD render diagnostic in Sentry", sentryFailure);
		}

		if (throwable == null) {
			log.warn(
				"{} (key={}, suppressed={}, maxVertices={}, maxFaces={}, maxDiameter={})",
				message,
				key,
				snapshot.suppressed,
				snapshot.maxVertices,
				snapshot.maxFaces,
				snapshot.maxDiameter
			);
		} else {
			log.warn(
				"{} (key={}, exception={}, suppressed={}, maxVertices={}, maxFaces={}, maxDiameter={})",
				message,
				key,
				throwable.toString(),
				snapshot.suppressed,
				snapshot.maxVertices,
				snapshot.maxFaces,
				snapshot.maxDiameter
			);
		}
	}

	private static void applyScope(
		IScope scope,
		String key,
		String message,
		SentryLevel level,
		Context context
	) {
		scope.setLevel(level);
		scope.setTag("117hd.renderDiagnostic", key);
		scope.setFingerprint(Arrays.asList("117hd-render-model", key));
		scope.setExtra("message", message);
		for (Map.Entry<String, Object> entry : context.extras.entrySet())
			scope.setExtra(entry.getKey(), String.valueOf(entry.getValue()));
	}

	private static final class RateLimitState {
		private long lastReportMillis;
		private int suppressed;
		private int maxVertices;
		private int maxFaces;
		private int maxDiameter;

		synchronized RateLimitSnapshot record(long nowMillis, Context context) {
			if (lastReportMillis != 0 && nowMillis - lastReportMillis < REPORT_INTERVAL_MILLIS) {
				suppressed++;
				maxVertices = Math.max(maxVertices, context.vertexCount());
				maxFaces = Math.max(maxFaces, context.faceCount());
				maxDiameter = Math.max(maxDiameter, context.diameter());
				return null;
			}

			RateLimitSnapshot snapshot = new RateLimitSnapshot(suppressed, maxVertices, maxFaces, maxDiameter);
			lastReportMillis = nowMillis;
			suppressed = 0;
			maxVertices = 0;
			maxFaces = 0;
			maxDiameter = 0;
			return snapshot;
		}
	}

	private static final class RateLimitSnapshot {
		private final int suppressed;
		private final int maxVertices;
		private final int maxFaces;
		private final int maxDiameter;

		private RateLimitSnapshot(int suppressed, int maxVertices, int maxFaces, int maxDiameter) {
			this.suppressed = suppressed;
			this.maxVertices = maxVertices;
			this.maxFaces = maxFaces;
			this.maxDiameter = maxDiameter;
		}
	}

	static final class Context {
		private final LinkedHashMap<String, Object> extras = new LinkedHashMap<>();
		private int vertexCount;
		private int faceCount;
		private int diameter;

		private Context(String renderPath) {
			extra("renderPath", renderPath);
		}

		private Context copy() {
			Context copy = new Context(String.valueOf(extras.get("renderPath")));
			copy.extras.clear();
			copy.extras.putAll(extras);
			copy.vertexCount = vertexCount;
			copy.faceCount = faceCount;
			copy.diameter = diameter;
			return copy;
		}

		Context worldView(@Nullable WorldViewContext ctx) {
			if (ctx == null)
				return this;
			extra("worldViewId", ctx.worldViewId);
			if (ctx.sceneContext != null) {
				extra("sceneOffset", ctx.sceneContext.sceneOffset);
				scene(ctx.sceneContext.scene);
			}
			if (ctx.uboWorldViewStruct != null)
				extra("worldViewIndex", ctx.uboWorldViewStruct.worldViewIdx);
			return this;
		}

		Context scene(@Nullable Scene scene) {
			if (scene == null)
				return this;
			return safeExtra("sceneWorldViewId", scene::getWorldViewId);
		}

		Context tile(@Nullable Tile tile) {
			if (tile == null)
				return this;
			safeExtra("tilePlane", tile::getPlane);
			safeExtra("tileRenderLevel", tile::getRenderLevel);
			try {
				Point sceneLocation = tile.getSceneLocation();
				if (sceneLocation != null) {
					extra("tileSceneX", sceneLocation.getX());
					extra("tileSceneY", sceneLocation.getY());
				}
			} catch (Throwable ignored) {
				// Diagnostics should never become the render failure.
			}
			return this;
		}

		Context tileObject(@Nullable TileObject tileObject) {
			if (tileObject == null)
				return this;
			safeExtra("objectId", tileObject::getId);
			safeExtra("objectHash", () -> Long.toUnsignedString(tileObject.getHash()));
			safeExtra("objectPlane", tileObject::getPlane);
			safeExtra("objectX", tileObject::getX);
			safeExtra("objectY", tileObject::getY);
			safeExtra("objectZ", tileObject::getZ);
			return this;
		}

		Context renderable(@Nullable Renderable renderable) {
			if (renderable == null)
				return this;
			extra("renderableType", renderable.getClass().getName());
			safeExtra("renderMode", renderable::getRenderMode);
			if (renderable instanceof Actor) {
				Actor actor = (Actor) renderable;
				safeExtra("actorName", actor::getName);
				try {
					LocalPoint localPoint = actor.getLocalLocation();
					if (localPoint != null) {
						extra("actorLocalX", localPoint.getX());
						extra("actorLocalY", localPoint.getY());
					}
				} catch (Throwable ignored) {
					// Diagnostics should never become the render failure.
				}
			}
			if (renderable instanceof Player) {
				Player player = (Player) renderable;
				safeExtra("playerId", player::getId);
				safeExtra("playerName", player::getName);
			} else if (renderable instanceof NPC) {
				NPC npc = (NPC) renderable;
				safeExtra("npcId", npc::getId);
				safeExtra("npcIndex", npc::getIndex);
				safeExtra("npcName", npc::getName);
			}
			return this;
		}

		Context model(@Nullable Model model) {
			if (model == null)
				return this;
			safeExtra("modelClass", () -> model.getClass().getName());
			safeExtra("modelSceneId", model::getSceneId);
			safeExtra("modelHash", () -> Long.toUnsignedString(model.getHash()));
			safeExtra("vertexCount", () -> vertexCount = model.getVerticesCount());
			safeExtra("faceCount", () -> faceCount = model.getFaceCount());
			safeExtra("modelRadius", model::getRadius);
			safeExtra("modelDiameter", () -> diameter = model.getDiameter());
			safeExtra("modelXYZMag", model::getXYZMag);
			safeExtra("modelHeight", model::getModelHeight);
			return this;
		}

		Context alphaModel(@Nullable Zone.AlphaModel model) {
			if (model == null)
				return this;
			extra("modelIdentity", model.id);
			extra("objectId", model.id);
			extra("alphaModelLevel", model.level);
			extra("alphaModelFlags", model.flags);
			extra("alphaModelRadius", model.radius);
			extra("modelRadius", model.radius);
			diameter = model.radius > 0 ? 1 + model.radius * 2 : 0;
			extra("modelDiameter", diameter);
			if (model.packedFaces != null) {
				faceCount = model.packedFaces.length;
				extra("faceCount", faceCount);
				extra("packedFaceCount", model.packedFaces.length);
			}
			if (model.sortedFaces != null)
				extra("sortedFaceCapacity", model.sortedFaces.length);
			extra("sortedFacesLen", model.sortedFacesLen);
			extra("positionX", model.x);
			extra("positionY", model.y);
			extra("positionZ", model.z);
			extra("vao", model.vao);
			extra("startpos", model.startpos);
			extra("endpos", model.endpos);
			return this;
		}

		Context modelOverride(@Nullable ModelOverride modelOverride) {
			if (modelOverride == null)
				return this;
			extra("modelOverride", modelOverride.description);
			extra("modelOverrideMightHaveTransparency", modelOverride.mightHaveTransparency);
			extra("modelOverrideBaseMaterial", modelOverride.baseMaterial);
			extra("modelOverrideTextureMaterial", modelOverride.textureMaterial);
			return this;
		}

		Context uuid(int uuid) {
			extra("modelIdentity", uuid);
			extra("modelUuid", Integer.toUnsignedString(uuid));
			extra("modelUuidType", ModelHash.getTypeName(ModelHash.getUuidType(uuid)));
			extra("modelUuidSubType", ModelHash.getUuidSubType(uuid));
			return this;
		}

		Context position(int orientation, int x, int y, int z) {
			extra("orientation", orientation);
			extra("positionX", x);
			extra("positionY", y);
			extra("positionZ", z);
			return this;
		}

		Context scratchLimits() {
			extra("scratchLimitVertices", SceneUploader.MAX_VERTEX_COUNT);
			extra("scratchLimitFaces", FacePrioritySorter.MAX_FACE_COUNT);
			extra("scratchLimitDiameter", FacePrioritySorter.MAX_DIAMETER);
			extra("scratchLimitFacesPerPriority", FacePrioritySorter.MAX_FACES_PER_PRIORITY);
			return this;
		}

		Context extra(String key, @Nullable Object value) {
			if (value != null)
				extras.put(key, value);
			return this;
		}

		private Context safeExtra(String key, ValueSupplier supplier) {
			try {
				extra(key, supplier.get());
			} catch (Throwable ignored) {
				// Diagnostics should never become the render failure.
			}
			return this;
		}

		private int vertexCount() {
			return vertexCount;
		}

		private int faceCount() {
			return faceCount;
		}

		private int diameter() {
			return diameter;
		}
	}

	@FunctionalInterface
	private interface ValueSupplier {
		Object get();
	}
}
