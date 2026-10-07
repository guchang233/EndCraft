package dev.skycraft.combat;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;

/** Normal MC passenger seats, with a heartbeat to move the original native actor. */
public final class SkyPassengers {
	private static final Map<Integer, Integer> WAITING = new HashMap<>();
	private static final Map<Integer, Long> RETRY_AT = new HashMap<>();

	private SkyPassengers() {}

	/** Returns true while MC's vehicle, rather than the host snapshot, owns this pose. */
	public static boolean sync(ServerLevel level, SkyrimActorEntity proxy, SkyLink.Actor actor) {
		if ((actor.flags() & Proto.ACTOR_RIDEABLE) == 0) {
			release(proxy);
			return false;
		}
		Entity vehicle = proxy.getVehicle();
		if (vehicle != null && (vehicle.isRemoved() || !(vehicle instanceof AbstractBoat || vehicle instanceof Minecart))) {
			release(proxy);
			vehicle = null;
		}
		if (vehicle == null && WAITING.containsKey(proxy.formId())) release(proxy);
		if (vehicle == null && level.getGameTime() >= RETRY_AT.getOrDefault(proxy.formId(), 0L)) {
			// Same proximity box as a boat's ordinary automatic pickup. startRiding
			// enforces vanilla seat capacity, existing passengers and ride cooldowns.
			for (Entity candidate : level.getEntities(proxy, proxy.getBoundingBox().inflate(0.2, 0.0, 0.2))) {
				if (!(candidate instanceof AbstractBoat || candidate instanceof Minecart) || candidate.isRemoved()) continue;
				proxy.setSize(Math.min(actor.width(), .8f), actor.height());
				if (proxy.startRiding(candidate)) {
					vehicle = candidate;
					WAITING.put(proxy.formId(), 0);
					break;
				}
			}
		}
		if (vehicle == null) return false;
		int missed = (actor.flags() & Proto.ACTOR_MOUNTED) != 0 ? 0 : WAITING.getOrDefault(proxy.formId(), 0) + 1;
		WAITING.put(proxy.formId(), missed);
		if (missed > 25) {
			// Native acquisition failed or was released. Never leave an invisible
			// passenger reserving a seat while the original NPC walks away.
			release(proxy);
			return false;
		}
		vehicle.positionRider(proxy);
		SkyLink.pushEvent(Proto.EV_ACTOR_VEHICLE, proxy.formId(), (float) proxy.getX(), (float) proxy.getY(),
			(float) proxy.getZ(), vehicle.getYRot(), 1, vehicle.getId());
		return true;
	}

	public static void release(SkyrimActorEntity proxy) {
		if (WAITING.remove(proxy.formId()) != null || proxy.isPassenger()) {
			proxy.stopRiding();
			SkyLink.pushEvent(Proto.EV_ACTOR_VEHICLE, proxy.formId(), (float) proxy.getX(), (float) proxy.getY(),
				(float) proxy.getZ(), proxy.getYRot(), 0, 0);
			RETRY_AT.put(proxy.formId(), proxy.level().getGameTime() + 40);
		}
	}

	public static void forget(SkyrimActorEntity proxy) {
		release(proxy);
		RETRY_AT.remove(proxy.formId());
	}
}
