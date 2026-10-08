package dev.skycraft.world;

import java.util.List;

/** Ray casts against exact Skyrim collision triangles (pure math, no Minecraft state). */
public final class SkyRay {
	private SkyRay() {
	}

	/** Nearest hit along a segment: {@code t} in [0, 1], the surface normal facing the ray origin, and the triangle. */
	public record Hit(double t, double x, double y, double z, double nx, double ny, double nz, SkyTri tri) {
	}

	/**
	 * First triangle hit on the segment from (fx, fy, fz) to (tx, ty, tz), or null. Skyrim's invisible
	 * stair ramps are skipped: projectiles and block placement should meet the visible steps.
	 */
	public static Hit cast(List<SkyTri> tris, double fx, double fy, double fz, double tx, double ty, double tz) {
		double dx = tx - fx, dy = ty - fy, dz = tz - fz;
		double best = Double.POSITIVE_INFINITY;
		SkyTri hitTri = null;
		for (SkyTri tri : tris) {
			if (tri.stairHelper) {
				continue;
			}
			double t = intersect(tri, fx, fy, fz, dx, dy, dz);
			if (t >= 0.0 && t <= 1.0 && t < best) {
				best = t;
				hitTri = tri;
			}
		}
		if (hitTri == null) {
			return null;
		}
		double nx = hitTri.nx, ny = hitTri.ny, nz = hitTri.nz;
		if (nx * dx + ny * dy + nz * dz > 0.0) {
			nx = -nx;
			ny = -ny;
			nz = -nz;
		}
		return new Hit(best, fx + dx * best, fy + dy * best, fz + dz * best, nx, ny, nz, hitTri);
	}

	/** Moller-Trumbore, two-sided. Returns the segment parameter or -1. */
	static double intersect(SkyTri tri, double ox, double oy, double oz, double dx, double dy, double dz) {
		double e1x = tri.bx - tri.ax, e1y = tri.by - tri.ay, e1z = tri.bz - tri.az;
		double e2x = tri.cx - tri.ax, e2y = tri.cy - tri.ay, e2z = tri.cz - tri.az;
		double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
		double det = e1x * px + e1y * py + e1z * pz;
		if (Math.abs(det) < 1e-12) {
			return -1.0;
		}
		double inv = 1.0 / det;
		double sx = ox - tri.ax, sy = oy - tri.ay, sz = oz - tri.az;
		double u = (sx * px + sy * py + sz * pz) * inv;
		if (u < -1e-9 || u > 1.0 + 1e-9) {
			return -1.0;
		}
		double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
		double v = (dx * qx + dy * qy + dz * qz) * inv;
		if (v < -1e-9 || u + v > 1.0 + 1e-9) {
			return -1.0;
		}
		return (e2x * qx + e2y * qy + e2z * qz) * inv;
	}

	/**
	 * Where a block placed against a Skyrim surface goes: the cell a little way out from the hit
	 * along the surface normal. Blocks then sit on uneven ground slightly sunk in (like Minecraft
	 * blocks on a slope) instead of floating above it or vanishing into it.
	 */
	public static int[] placementCell(Hit hit) {
		double out = 0.4;
		return new int[] {
			(int) Math.floor(hit.x + hit.nx * out), (int) Math.floor(hit.y + hit.ny * out), (int) Math.floor(hit.z + hit.nz * out)
		};
	}

	/** The cell just inside the surface (what a projectile is stuck in). */
	public static int[] surfaceCell(Hit hit) {
		double in = 0.01;
		return new int[] {
			(int) Math.floor(hit.x - hit.nx * in), (int) Math.floor(hit.y - hit.ny * in), (int) Math.floor(hit.z - hit.nz * in)
		};
	}

	/** Index into {up, down, north, south, west, east}-style axis choice: the dominant normal axis and sign. */
	public static int dominantFace(double nx, double ny, double nz) {
		double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
		if (ay >= ax && ay >= az) {
			return ny >= 0 ? 1 : 0; // UP : DOWN
		}
		if (ax >= az) {
			return nx >= 0 ? 5 : 4; // EAST : WEST
		}
		return nz >= 0 ? 3 : 2; // SOUTH : NORTH
	}
}
