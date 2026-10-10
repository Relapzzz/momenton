package com.relapzz.momenton.record;

import com.relapzz.momenton.mixin.client.ServerStateBossBarHudAccessor;
import com.relapzz.momenton.mixin.client.ServerStateCooldownEntryAccessor;
import com.relapzz.momenton.mixin.client.ServerStateItemCooldownManagerAccessor;
import com.relapzz.momenton.mixin.client.ServerStatePlayerListHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BossBarS2CPacket;
import net.minecraft.network.packet.s2c.play.CooldownUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListHeaderS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.TeamS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldBorderInitializeS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class ServerStateSnapshot {
	private ServerStateSnapshot() {
	}

	static void capture(MinecraftClient client, List<Packet<?>> packets) {
		ClientWorld world = client.world;
		addScoreboard(world.getScoreboard(), packets);

		for (ClientBossBar bar : ((ServerStateBossBarHudAccessor) client.inGameHud.getBossBarHud()).momenton$getBossBars().values()) {
			packets.add(BossBarS2CPacket.add(bar));
		}

		ServerStatePlayerListHudAccessor tabList = (ServerStatePlayerListHudAccessor) client.inGameHud.getPlayerListHud();
		Text header = tabList.momenton$getHeader();
		Text footer = tabList.momenton$getFooter();
		if (header != null || footer != null) {
			packets.add(new PlayerListHeaderS2CPacket(
					Objects.requireNonNullElse(header, Text.empty()), Objects.requireNonNullElse(footer, Text.empty())));
		}

		packets.add(new WorldBorderInitializeS2CPacket(world.getWorldBorder()));
		packets.add(new GameStateChangeS2CPacket(world.getLevelProperties().isRaining()
				? GameStateChangeS2CPacket.RAIN_STARTED
				: GameStateChangeS2CPacket.RAIN_STOPPED, 0.0f));
		packets.add(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED, world.getRainGradient(1.0f)));
		packets.add(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED, world.getThunderGradient(1.0f)));

		packets.add(new PlayerAbilitiesS2CPacket(client.player.getAbilities()));

		ServerStateItemCooldownManagerAccessor cooldowns = (ServerStateItemCooldownManagerAccessor) client.player.getItemCooldownManager();
		cooldowns.momenton$getEntries().forEach((group, entry) -> packets.add(new CooldownUpdateS2CPacket(group,
				((ServerStateCooldownEntryAccessor) entry).momenton$getEndTick() - cooldowns.momenton$getTick())));
	}

	private static void addScoreboard(Scoreboard scoreboard, List<Packet<?>> packets) {
		for (Team team : scoreboard.getTeams()) {
			packets.add(TeamS2CPacket.updateTeam(team, true));
		}
		for (ScoreboardObjective objective : scoreboard.getObjectives()) {
			packets.add(new ScoreboardObjectiveUpdateS2CPacket(objective, ScoreboardObjectiveUpdateS2CPacket.ADD_MODE));
			for (ScoreboardDisplaySlot slot : ScoreboardDisplaySlot.values()) {
				if (scoreboard.getObjectiveForSlot(slot) == objective) {
					packets.add(new ScoreboardDisplayS2CPacket(slot, objective));
				}
			}
			for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(objective)) {
				packets.add(new ScoreboardScoreUpdateS2CPacket(entry.owner(), objective.getName(), entry.value(),
						Optional.ofNullable(entry.display()), Optional.ofNullable(entry.numberFormatOverride())));
			}
		}
	}
}
