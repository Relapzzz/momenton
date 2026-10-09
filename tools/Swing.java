import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayReader;
import com.relapzz.momenton.format.UiStateCodec;

import java.nio.file.Path;

/**
 * The swing and cooldown halves of the UI track, and whether playback can drive an animation from
 * them.
 *
 * <p>UiStateDriver replays swing *starts*, not swing numbers, so the track only works if the recorded
 * handSwingTicks sequence is one vanilla's tickHandSwing could have produced: a start at 0, then +1
 * per tick, then either a clear or a restart back to 0. A gap means a tick of the track went missing
 * and the replayed arm will run out of step with the recording.
 *
 * <p>Also counts restarts, which are the case a rising edge of handSwinging alone cannot see: swinging
 * stays set and only the counter drops, so spam-clicking is one swing followed by a still arm.
 */
public final class Swing {
	public static void main(String[] args) throws Exception {
		ReplayReader reader = new ReplayReader(Path.of(args[0]));
		ReplayMetadata metadata = reader.metadata();

		int starts = 0;
		int restarts = 0;
		int cooldownResets = 0;
		int gaps = 0;
		boolean swinging = false;
		int swingTicks = 0;
		float cooldown = -1.0f;
		int chunkStart = -1;

		for (ReplayMetadata.ChunkEntry entry : metadata.chunks()) {
			ActionCodec.Reader r = reader.ui(entry);
			chunkStart = entry.startTick();
			ActionCodec.Action a;
			while ((a = r.next()) != null) {
				if (a.type() != ActionCodec.UI_STATE) {
					continue;
				}
				UiStateCodec.State s = UiStateCodec.decode(a.payload());

				if (s.has(UiStateCodec.FLAG_SWING)) {
					boolean start = s.swinging() && (!swinging || s.swingTicks() < swingTicks);
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
					if (cooldown >= 0.0f && s.cooldown() < cooldown) {
						cooldownResets++;
					}
					cooldown = s.cooldown();
				}
			}
		}
		reader.close();

		System.out.printf("%d swing starts (%d of them restarts mid-swing), %d cooldown resets%n",
				starts, restarts, cooldownResets);
		System.out.println(gaps == 0 ? "ALL PASS" : gaps + " FAILED");
		if (gaps != 0) {
			System.exit(1);
		}
	}
}
