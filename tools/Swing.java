import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayReader;
import com.relapzz.momenton.format.UiStateCodec;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * The swing, cooldown and counter-reset parts of the UI track, and whether playback can drive an
 * animation from them.
 *
 * <p>UiStateDriver replays swing *starts*, not swing numbers, so the track only works if the recorded
 * handSwingTicks sequence is one vanilla's tickHandSwing could have produced: a start at 0, then +1
 * per tick, then either a clear or a restart back to 0. A gap means a tick of the track went missing
 * and the replayed arm will run out of step with the recording.
 *
 * <p>Also counts restarts, which are the case a rising edge of handSwinging alone cannot see: swinging
 * stays set and only the counter drops, so spam-clicking is one swing followed by a still arm.
 *
 * <p>An attack resets two counters on the live client, ticksSinceLastAttack (the cooldown) and
 * ticksSinceHandEquipping (the first-person item dipping and rising again); the recorder writes each
 * reset as an event. A cooldown drop with no recorded reset is an attack-speed change, which a
 * recording without the events cannot tell from an attack. Optional second and third arguments print
 * every swing, cooldown and reset in that tick range.
 */
public final class Swing {
	public static void main(String[] args) throws Exception {
		ReplayReader reader = new ReplayReader(Path.of(args[0]));
		ReplayMetadata metadata = reader.metadata();
		int from = args.length > 2 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;
		int to = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MIN_VALUE;

		int starts = 0;
		int restarts = 0;
		int cooldownResets = 0;
		int attacks = 0;
		int bothResets = 0;
		int attackResets = 0;
		int unexplainedDrops = 0;
		int gaps = 0;
		boolean resetsRecorded = false;
		boolean swinging = false;
		int swingTicks = 0;
		float cooldown = -1.0f;
		Map<Float, Integer> firstSteps = new TreeMap<>();

		for (ReplayMetadata.ChunkEntry entry : metadata.chunks()) {
			ActionCodec.Reader r = reader.ui(entry);
			int chunkStart = entry.startTick();
			ActionCodec.Action a;
			while ((a = r.next()) != null) {
				if (a.type() != ActionCodec.UI_STATE) {
					continue;
				}
				UiStateCodec.State s = UiStateCodec.decode(a.payload());
				boolean start = false;
				boolean reset = false;

				if (s.has(UiStateCodec.FLAG_SWING)) {
					start = s.swinging() && (!swinging || s.swingTicks() < swingTicks);
					if (start) {
						starts++;
						if (swinging) {
							restarts++;
						}
						if (s.swingTicks() != 0 && a.tick() != chunkStart) {
							System.out.printf("FAIL tick %d: a swing starts at handSwingTicks %d, not 0%n",
									a.tick(), s.swingTicks());
							gaps++;
						}
					} else if (s.swinging() && s.swingTicks() != swingTicks + 1 && a.tick() != chunkStart) {
						System.out.printf("FAIL tick %d: handSwingTicks jumped %d -> %d%n",
								a.tick(), swingTicks, s.swingTicks());
						gaps++;
					}
					swinging = s.swinging();
					swingTicks = s.swingTicks();
				}

				if (s.has(UiStateCodec.FLAG_COOLDOWN)) {
					reset = cooldown >= 0.0f && s.cooldown() < cooldown;
					if (reset) {
						cooldownResets++;
						firstSteps.merge(s.cooldown(), 1, Integer::sum);
						if (start) {
							attacks++;
						}
					}
					cooldown = s.cooldown();
				}

				if (s.has(UiStateCodec.FLAG_RESETS)) {
					resetsRecorded = true;
				}
				boolean both = (s.resets() & UiStateCodec.RESET_ATTACK_AND_EQUIP) != 0;
				boolean attackOnly = !both && (s.resets() & UiStateCodec.RESET_ATTACK) != 0;
				if (both) {
					bothResets++;
				}
				if (attackOnly) {
					attackResets++;
				}
				if (resetsRecorded && reset && s.resets() == 0) {
					unexplainedDrops++;
				}

				if (a.tick() >= from && a.tick() <= to) {
					System.out.printf("t=%-5d%s swing=%-5s ticks=%-2d cooldown=%.3f%s%s%n", a.tick(),
							start ? " START" : "      ", swinging, swingTicks, cooldown, reset ? " DROP" : "     ",
							both ? " resetTicksSince" : attackOnly ? " resetTicksSinceLastAttack" : "");
				}
			}
		}
		reader.close();

		System.out.printf("%d swing starts (%d of them restarts mid-swing), %d cooldown drops (%d on a swing start)%n",
				starts, restarts, cooldownResets, attacks);
		System.out.println(resetsRecorded
				? String.format("%d resetTicksSince, %d resetTicksSinceLastAttack, %d cooldown drops with no reset",
						bothResets, attackResets, unexplainedDrops)
				: "no reset events (recorded before they existed); playback calls resetTicksSince on every cooldown drop");
		System.out.println("cooldown just after a drop, 1 / ticks to full charge when the drop is a reset: " + firstSteps);
		System.out.println(gaps == 0 ? "ALL PASS" : gaps + " FAILED");
		if (gaps != 0) {
			System.exit(1);
		}
	}
}
