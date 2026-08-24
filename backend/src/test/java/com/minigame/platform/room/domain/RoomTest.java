package com.minigame.platform.room.domain;

import com.minigame.platform.auth.domain.ActorId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomTest {
    @Test
    void hostDoesNotReadyAndOtherActivePlayersUnlockStart() {
        var room = RoomFixture.roomWithFourParticipants();

        assertThatThrownBy(() -> room.changeReady(
            RoomFixture.HOST,
            true,
            RoomFixture.requestId("host-ready")
        ))
            .isInstanceOfSatisfying(RoomRuleViolation.class,
                error -> assertThat(error.code()).isEqualTo("ROOM_HOST_CANNOT_READY"));

        for (var actorId : List.of(RoomFixture.GUEST_1, RoomFixture.GUEST_2, RoomFixture.GUEST_3)) {
            room.changeReady(actorId, true, RoomFixture.requestId("ready-" + actorId.value()));
        }

        assertThat(room.snapshot().participantsReadyToStart()).isTrue();
    }

    @Test
    void settingChangeClearsEveryReadyParticipant() {
        var room = RoomFixture.roomWithFourParticipants();
        room.changeReady(RoomFixture.GUEST_1, true, RoomFixture.requestId("ready-guest"));

        var events = room.updateSettings(
            RoomFixture.HOST,
            new RoomSettings(GameType.LIAR, 10, 3, 30, 90, "family"),
            RoomFixture.requestId("clear-ready-settings")
        );

        assertThat(room.snapshot().participants()).allMatch(participant -> !participant.ready());
        assertThat(events).containsExactly(
            new RoomEvent.SettingsUpdated(5, new RoomSettings(GameType.LIAR, 10, 3, 30, 90, "family"), false)
        );
    }

    @Test
    void rejectsMaximumBelowCurrentParticipantCount() {
        var room = RoomFixture.roomWithFourParticipants();

        assertThatThrownBy(() -> room.updateSettings(
            RoomFixture.HOST,
            new RoomSettings(GameType.DRAWING, 3, 3, 30, 90, "all"),
            RoomFixture.requestId("max-below-current")
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
            RoomFixture.requestId("only-host-settings")
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
        room.join(RoomFixture.GUEST_1, "참가자1", false, RoomFixture.requestId("capacity-join-1"));

        assertThatThrownBy(() -> room.join(
            RoomFixture.GUEST_2,
            "참가자2",
            false,
            RoomFixture.requestId("capacity-join-2")
        ))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_FULL");
    }

    @Test
    void duplicateRequestDoesNotApplyTheSameCommandTwice() {
        var room = RoomFixture.emptyRoom();

        var requestId = RoomFixture.requestId("duplicate-join");
        var first = room.join(RoomFixture.GUEST_1, "참가자1", false, requestId);
        var duplicate = room.join(RoomFixture.GUEST_1, "참가자1", false, requestId);

        assertThat(first).hasSize(1);
        assertThat(duplicate).isEmpty();
        assertThat(room.snapshot().participants()).hasSize(2);
        assertThat(room.snapshot().sequence()).isEqualTo(1);
    }

    @Test
    void sameRequestIdFromDifferentActorsDoesNotSuppressEitherCommand() {
        var room = RoomFixture.emptyRoom();
        var sharedRequestId = "00000000-0000-0000-0000-000000000901";

        var first = room.join(RoomFixture.GUEST_1, "참가자1", false, sharedRequestId);
        var second = room.join(RoomFixture.GUEST_2, "참가자2", false, sharedRequestId);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(room.snapshot().participants()).hasSize(3);
    }

    @Test
    void sameActorCanReuseRequestIdForADifferentCommandType() {
        var room = RoomFixture.emptyRoom();
        var sharedRequestId = "00000000-0000-0000-0000-000000000902";

        room.join(RoomFixture.GUEST_1, "참가자1", false, sharedRequestId);
        var leave = room.leave(RoomFixture.GUEST_1, sharedRequestId);

        assertThat(leave).hasSize(1);
        assertThat(room.snapshot().sequence()).isEqualTo(2);
    }

    @Test
    void rejectsNonUuidRequestIds() {
        var room = RoomFixture.emptyRoom();

        assertThatThrownBy(() -> room.changeReady(RoomFixture.HOST, true, "not-a-uuid"))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_REQUEST_ID_INVALID");
    }

    @Test
    void failedAuthorizationDoesNotConsumeTheRequestId() {
        var room = RoomFixture.roomWithFourParticipants();
        var next = room.snapshot().settings();

        var requestId = RoomFixture.requestId("failed-authorization");
        assertThatThrownBy(() -> room.updateSettings(RoomFixture.GUEST_1, next, requestId))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_HOST_REQUIRED");
        assertThatThrownBy(() -> room.updateSettings(RoomFixture.GUEST_1, next, requestId))
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
        room.join(RoomFixture.GUEST_1, "참가자1", false, RoomFixture.requestId("retry-seat-join"));

        var retryRequestId = RoomFixture.requestId("retry-capacity");
        assertThatThrownBy(() -> room.join(RoomFixture.GUEST_2, "참가자2", false, retryRequestId))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_FULL");
        room.leave(RoomFixture.GUEST_1, RoomFixture.requestId("retry-seat-leave"));

        assertThat(room.join(RoomFixture.GUEST_2, "참가자2", false, retryRequestId)).hasSize(1);
        assertThat(room.snapshot().participants())
            .extracting(Participant::actorId)
            .contains(RoomFixture.GUEST_2);
    }

    @Test
    void transferredHostImmediatelyOwnsManagementAuthority() {
        var room = RoomFixture.roomWithFourParticipants();

        room.transferHost(RoomFixture.HOST, RoomFixture.GUEST_1, RoomFixture.requestId("transfer"));

        assertThat(room.snapshot().hostId()).isEqualTo(RoomFixture.GUEST_1);
        assertThatThrownBy(() -> room.updateSettings(
            RoomFixture.HOST,
            room.snapshot().settings(),
            RoomFixture.requestId("old-host-settings")
        ))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_HOST_REQUIRED");
        assertThat(room.updateSettings(
            RoomFixture.GUEST_1,
            room.snapshot().settings(),
            RoomFixture.requestId("new-host-settings")
        )).hasSize(1);
    }

    @Test
    void transferringHostClearsTheNewHostsReadyStateAndPublishesFreshStartEligibility() {
        var room = RoomFixture.roomWithFourParticipants();
        readyAllGuests(room);

        var events = room.transferHost(RoomFixture.HOST, RoomFixture.GUEST_1, RoomFixture.requestId("transfer-ready"));

        assertThat(room.snapshot().participants())
            .filteredOn(participant -> participant.actorId().equals(RoomFixture.GUEST_1))
            .allMatch(participant -> !participant.ready());
        assertThat(room.snapshot().participantsReadyToStart()).isFalse();
        assertThat(events).containsExactly(
            new RoomEvent.HostTransferred(7, RoomFixture.HOST, RoomFixture.GUEST_1, false)
        );
    }

    @Test
    void hostLeaveTransfersAuthorityToTheOldestActiveParticipant() {
        var room = RoomFixture.roomWithFourParticipants();

        var events = room.leave(RoomFixture.HOST, RoomFixture.requestId("host-leave"));

        assertThat(room.snapshot().hostId()).isEqualTo(RoomFixture.GUEST_1);
        assertThat(events).containsExactly(
            new RoomEvent.ParticipantLeft(4, RoomFixture.HOST, false),
            new RoomEvent.HostTransferred(5, RoomFixture.HOST, RoomFixture.GUEST_1, false)
        );
    }

    @Test
    void hostLeaveClearsTheReplacementHostsReadyStateAndPublishesFreshStartEligibility() {
        var room = RoomFixture.roomWithFourParticipants();
        readyAllGuests(room);

        var events = room.leave(RoomFixture.HOST, RoomFixture.requestId("host-leave-ready"));

        assertThat(room.snapshot().participants())
            .filteredOn(participant -> participant.actorId().equals(RoomFixture.GUEST_1))
            .allMatch(participant -> !participant.ready());
        assertThat(room.snapshot().participantsReadyToStart()).isFalse();
        assertThat(events).containsExactly(
            new RoomEvent.ParticipantLeft(7, RoomFixture.HOST, false),
            new RoomEvent.HostTransferred(8, RoomFixture.HOST, RoomFixture.GUEST_1, false)
        );
    }

    @Test
    void activeJoinClearsExistingReadinessWhileSpectatorJoinPreservesIt() {
        var room = RoomFixture.roomWithFourParticipants();
        readyAllGuests(room);

        room.join(new ActorId("spectator"), "관전자", true, RoomFixture.requestId("spectator-join"));
        assertThat(room.snapshot().participantsReadyToStart()).isTrue();

        room.join(new ActorId("new-player"), "새 참가자", false, RoomFixture.requestId("active-join"));
        assertThat(room.snapshot().participantsReadyToStart()).isFalse();
        assertThat(room.snapshot().participants())
            .filteredOn(participant -> !participant.spectator())
            .filteredOn(participant -> !participant.actorId().equals(RoomFixture.HOST))
            .allMatch(participant -> !participant.ready());
    }

    @Test
    void lastParticipantLeaveClosesTheRoom() {
        var room = RoomFixture.emptyRoom();

        var events = room.leave(RoomFixture.HOST, RoomFixture.requestId("last-leave"));

        assertThat(room.snapshot().status()).isEqualTo(RoomStatus.CLOSED);
        assertThat(events).containsExactly(
            new RoomEvent.ParticipantLeft(1, RoomFixture.HOST, false),
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
        room.join(RoomFixture.GUEST_1, "참가자1", false, RoomFixture.requestId("active"));
        room.join(RoomFixture.GUEST_2, "관전자", true, RoomFixture.requestId("spectator"));

        assertThat(room.snapshot().participants()).hasSize(3);
        assertThatThrownBy(() -> room.changeReady(
            RoomFixture.GUEST_2,
            true,
            RoomFixture.requestId("spectator-ready")
        ))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_SPECTATOR_CANNOT_READY");
    }

    @Test
    void rejectsUnknownParticipantCommands() {
        var room = RoomFixture.emptyRoom();

        assertThatThrownBy(() -> room.changeReady(
            new ActorId("unknown"),
            true,
            RoomFixture.requestId("unknown-ready")
        ))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_PARTICIPANT_NOT_FOUND");
    }

    private static void readyAllGuests(Room room) {
        for (var actorId : List.of(RoomFixture.GUEST_1, RoomFixture.GUEST_2, RoomFixture.GUEST_3)) {
            room.changeReady(actorId, true, RoomFixture.requestId("ready-" + actorId.value()));
        }
    }
}
