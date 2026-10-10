package com.relapzz.momenton.format;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiStateCodecTest {
	@Test
	void payloadWithoutResetsDecodesAsBefore() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(UiStateCodec.FLAG_SWING | UiStateCodec.FLAG_COOLDOWN | UiStateCodec.FLAG_CHAT_DRAFT);
		out.writeByte(UiStateCodec.SWING_ACTIVE);
		ActionCodec.writeVarInt(out, 0);
		out.writeFloat(0.08f);
		out.writeUTF("hi");

		UiStateCodec.State state = UiStateCodec.decode(bytes.toByteArray());
		assertFalse(state.has(UiStateCodec.FLAG_RESETS));
		assertEquals(0, state.resets());
		assertEquals(0.08f, state.cooldown());
		assertEquals("hi", state.chatDraft());
	}

	@Test
	void resetsFollowTheChatDraft() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(UiStateCodec.FLAG_COOLDOWN | UiStateCodec.FLAG_CHAT_DRAFT | UiStateCodec.FLAG_RESETS);
		out.writeFloat(0.0f);
		out.writeUTF("");
		out.writeByte(UiStateCodec.RESET_ATTACK | UiStateCodec.RESET_ATTACK_AND_EQUIP);

		UiStateCodec.State state = UiStateCodec.decode(bytes.toByteArray());
		assertTrue(state.has(UiStateCodec.FLAG_RESETS));
		assertEquals(UiStateCodec.RESET_ATTACK | UiStateCodec.RESET_ATTACK_AND_EQUIP, state.resets());
	}
}
