package com.minigame.platform.room.domain;

import com.minigame.platform.auth.domain.ActorId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomTest {
    @Test
    void settingChangeClearsEveryReadyParticipant() {
        var room = RoomFixture.roomWithFourParticipants();
        room.changeReady(RoomFixture.HOST, true, "req-ready-host");
        room.changeReady(RoomFixture.GUEST_1, true, "req-ready-guest");

        var events = room.updateSettings(
            RoomFixture.HOST,
            new RoomSettings(GameType.LIAR, 10, 3, 30, 90, "family"),
            "req-settings"
        );

        assertThat(room.snapshot().participants()).allMatch(participant -> !participant.ready());
        assertThat(events).containsExactly(
            new RoomEvent.SettingsUpdated(6, new RoomSettings(GameType.LIAR, 10, 3, 30, 90, "family"))
        );
    }

    @Test
    void rejectsMaximumBelowCurrentParticipantCount() {
        var room = RoomFixture.roomWithFourParticipants();

        assertThatThrownBy(() -> room.updateSettings(
            RoomFixture.HOST,
            new RoomSettings(GameType.DRAWING, 3, 3, 30, 90, "all"),
            "req-settings"
        ))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_MAX_PLAYERS_TOO_SMALL");
    }

    @Test
    void rejectsSettingsOutsideTheSelectedGamesParticipantRange() {
        assertThatThrownBy(() -> new RoomSettings(GameType.LIAR, 11, 3, 30, 90, "all"))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_MAX_PLAYERS_OUT_OF_RANGE");
    }

    @Test
    void rejectsMaximumBelowTheSelectedGamesMinimumParticipantCount() {
        assertThatThrownBy(() -> new RoomSettings(GameType.LIAR, 3, 3, 30, 90, "all"))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_MAX_PLAYERS_OUT_OF_RANGE");
    }

    @Test
    void onlyTheCurrentHostCanUpdateSettings() {
        var room = RoomFixture.roomWithFourParticipants();

        assertThatThrownBy(() -> room.updateSettings(
            RoomFixture.GUEST_1,
            room.snapshot().settings(),
            "req-settings"
        ))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_HOST_REQUIRED");
    }

    @Test
    void joinRejectsParticipantsBeyondTheConfiguredMaximum() {
        var room = Room.create(
            RoomFixture.ROOM_ID,
            RoomFixture.ROOM_CODE,
            "그림방",
            Visibility.PUBLIC,
            new RoomSettings(GameType.DRAWING, 2, 1, 80, 1, "all"),
            RoomFixture.HOST,
            "방장"
        );
        room.join(RoomFixture.GUEST_1, "참가자1", false, "req-join-1");

        assertThatThrownBy(() -> room.join(RoomFixture.GUEST_2, "참가자2", false, "req-join-2"))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_FULL");
    }

    @Test
    void duplicateRequestDoesNotApplyTheSameCommandTwice() {
        var room = RoomFixture.emptyRoom();

        var first = room.join(RoomFixture.GUEST_1, "참가자1", false, "req-join");
        var duplicate = room.join(RoomFixture.GUEST_1, "참가자1", false, "req-join");

        assertThat(first).hasSize(1);
        assertThat(duplicate).isEmpty();
        assertThat(room.snapshot().participants()).hasSize(2);
        assertThat(room.snapshot().sequence()).isEqualTo(1);
    }

    @Test
    void failedAuthorizationDoesNotConsumeTheRequestId() {
        var room = RoomFixture.roomWithFourParticipants();
        var next = room.snapshot().settings();

        assertThatThrownBy(() -> room.updateSettings(RoomFixture.GUEST_1, next, "req-settings"))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_HOST_REQUIRED");
        assertThatThrownBy(() -> room.updateSettings(RoomFixture.GUEST_1, next, "req-settings"))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_HOST_REQUIRED");
    }

    @Test
    void failedCapacityCheckCanSucceedWithTheSameRequestIdAfterASeatOpens() {
        var room = Room.create(
            RoomFixture.ROOM_ID,
            RoomFixture.ROOM_CODE,
            "그림방",
            Visibility.PUBLIC,
            new RoomSettings(GameType.DRAWING, 2, 1, 80, 1, "all"),
            RoomFixture.HOST,
            "방장"
        );
        room.join(RoomFixture.GUEST_1, "참가자1", false, "req-join-1");

        assertThatThrownBy(() -> room.join(RoomFixture.GUEST_2, "참가자2", false, "req-retry-join"))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_FULL");
        room.leave(RoomFixture.GUEST_1, "req-leave");

        assertThat(room.join(RoomFixture.GUEST_2, "참가자2", false, "req-retry-join")).hasSize(1);
        assertThat(room.snapshot().participants())
            .extracting(Participant::actorId)
            .contains(RoomFixture.GUEST_2);
    }

    @Test
    void transferredHostImmediatelyOwnsManagementAuthority() {
        var room = RoomFixture.roomWithFourParticipants();

        room.transferHost(RoomFixture.HOST, RoomFixture.GUEST_1, "req-transfer");

        assertThat(room.snapshot().hostId()).isEqualTo(RoomFixture.GUEST_1);
        assertThatThrownBy(() -> room.updateSettings(
            RoomFixture.HOST,
            room.snapshot().settings(),
            "req-old-host-settings"
        ))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_HOST_REQUIRED");
        assertThat(room.updateSettings(
            RoomFixture.GUEST_1,
            room.snapshot().settings(),
            "req-new-host-settings"
        )).hasSize(1);
    }

    @Test
    void hostLeaveTransfersAuthorityToTheOldestActiveParticipant() {
        var room = RoomFixture.roomWithFourParticipants();

        var events = room.leave(RoomFixture.HOST, "req-leave");

        assertThat(room.snapshot().hostId()).isEqualTo(RoomFixture.GUEST_1);
        assertThat(events).containsExactly(
            new RoomEvent.ParticipantLeft(4, RoomFixture.HOST),
            new RoomEvent.HostTransferred(5, RoomFixture.HOST, RoomFixture.GUEST_1)
        );
    }

    @Test
    void lastParticipantLeaveClosesTheRoom() {
        var room = RoomFixture.emptyRoom();

        var events = room.leave(RoomFixture.HOST, "req-leave");

        assertThat(room.snapshot().status()).isEqualTo(RoomStatus.CLOSED);
        assertThat(events).containsExactly(
            new RoomEvent.ParticipantLeft(1, RoomFixture.HOST),
            new RoomEvent.RoomClosed(2)
        );
    }

    @Test
    void spectatorDoesNotConsumeAnActiveParticipantSeatAndCannotReady() {
        var room = Room.create(
            RoomFixture.ROOM_ID,
            RoomFixture.ROOM_CODE,
            "관전방",
            Visibility.PRIVATE,
            new RoomSettings(GameType.DRAWING, 2, 1, 80, 1, "all"),
            RoomFixture.HOST,
            "방장"
        );
        room.join(RoomFixture.GUEST_1, "참가자1", false, "req-active");
        room.join(RoomFixture.GUEST_2, "관전자", true, "req-spectator");

        assertThat(room.snapshot().participants()).hasSize(3);
        assertThatThrownBy(() -> room.changeReady(RoomFixture.GUEST_2, true, "req-ready"))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_SPECTATOR_CANNOT_READY");
    }

    @Test
    void rejectsUnknownParticipantCommands() {
        var room = RoomFixture.emptyRoom();

        assertThatThrownBy(() -> room.changeReady(new ActorId("unknown"), true, "req-ready"))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_PARTICIPANT_NOT_FOUND");
    }
}
