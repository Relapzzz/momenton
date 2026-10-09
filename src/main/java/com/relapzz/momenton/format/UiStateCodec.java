package com.relapzz.momenton.format;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * The payload inside a {@code UI_STATE} action: {@code [byte flags]} then only the sections the flags
 * name.
 *
 * <p>This is the state that never crosses the wire in either direction a replay can see. The selected
 * hotbar slot only travels client to server; pressing E produces no packet at all; hand swing and
 * attack cooldown are driven by local input. Without it a replay renders the right world under the
 * wrong HUD.
 *
 * <p>Change-gated and reset at every chunk boundary, so the first action of a chunk carries every
 * field and a seek into the middle of a recording still learns the whole state.
 */
public final class UiStateCodec {
	public static final int FLAG_SELECTED_SLOT = 1;
	public static final int FLAG_SCREEN = 2;
	public static final int FLAG_CHAT_SCROLL = 4;
	public static final int FLAG_SWING = 8;
	public static final int FLAG_COOLDOWN = 16;

	public static final int SCREEN_NONE = 0;
	public static final int SCREEN_OWN_INVENTORY = 1;
	public static final int SCREEN_CHAT = 2;
	public static final int SCREEN_OTHER = 3;

	/** Bits of the swing byte. */
	public static final int SWING_ACTIVE = 1;
	public static final int SWING_OFF_HAND = 2;

	private UiStateCodec() {
	}

	/** One decoded action. {@code flags} says which of the rest mean anything. */
	public record State(int flags, int selectedSlot, int screen, int chatScroll, int swing,
			int swingTicks, float cooldown) {

		public boolean has(int flag) {
			return (flags & flag) != 0;
		}

		public boolean swinging() {
			return (swing & SWING_ACTIVE) != 0;
		}

		public boolean offHand() {
			return (swing & SWING_OFF_HAND) != 0;
		}
	}

	public static State decode(byte[] payload) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
		int flags = in.readUnsignedByte();

		int selectedSlot = (flags & FLAG_SELECTED_SLOT) != 0 ? in.readByte() : 0;
		int screen = (flags & FLAG_SCREEN) != 0 ? in.readByte() : 0;
		int chatScroll = (flags & FLAG_CHAT_SCROLL) != 0 ? ActionCodec.readVarInt(in) : 0;

		int swing = 0;
		int swingTicks = 0;
		if ((flags & FLAG_SWING) != 0) {
			swing = in.readByte();
			swingTicks = ActionCodec.readVarInt(in);
		}

		float cooldown = (flags & FLAG_COOLDOWN) != 0 ? in.readFloat() : 0.0f;
		return new State(flags, selectedSlot, screen, chatScroll, swing, swingTicks, cooldown);
	}
}
