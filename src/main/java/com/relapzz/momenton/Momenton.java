package com.relapzz.momenton;

import io.netty.util.AttributeKey;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Momenton is almost entirely client-side; see {@link MomentonClient}.
 */
public class Momenton implements ModInitializer {
	public static final String MOD_ID = "momenton";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * Marks a channel as a replay rather than a server. Playback feeds recorded packets through a
	 * real {@code ClientConnection}, so the recording tap sees them again; without this the recorder
	 * would treat a replay's own setup packets as a new session's.
	 *
	 * <p>It lives here because the recorder must not depend on the playback package.
	 */
	public static final AttributeKey<Boolean> PLAYBACK = AttributeKey.valueOf(MOD_ID + ":playback");

	@Override
	public void onInitialize() {
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}
