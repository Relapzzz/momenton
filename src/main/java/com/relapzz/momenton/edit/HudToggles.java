package com.relapzz.momenton.edit;

import java.util.EnumSet;
import java.util.Set;

public record HudToggles(Set<Element> hidden) {
	public static final HudToggles ALL_SHOWN = new HudToggles(Set.of());

	public enum Element {
		HOTBAR, STATUS_BARS, EXPERIENCE, CROSSHAIR, CHAT, BOSS_BAR, STATUS_EFFECTS, TITLE, ACTION_BAR, SCOREBOARD
	}

	public HudToggles {
		hidden = Set.copyOf(hidden);
	}

	public boolean shows(Element element) {
		return !hidden.contains(element);
	}

	public HudToggles toggle(Element element) {
		Set<Element> edited = EnumSet.noneOf(Element.class);
		edited.addAll(hidden);
		if (!edited.remove(element)) edited.add(element);
		return new HudToggles(edited);
	}
}
