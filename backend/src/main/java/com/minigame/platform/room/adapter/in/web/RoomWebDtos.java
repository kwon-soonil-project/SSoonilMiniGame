package com.minigame.platform.room.adapter.in.web;

import com.minigame.platform.room.domain.GameType;
import com.minigame.platform.room.domain.RoomStatus;
import com.minigame.platform.room.domain.Visibility;
import com.minigame.platform.room.application.RoomApplicationService.LobbyRoomView;
import com.minigame.platform.room.application.RoomApplicationService.ParticipantView;
import com.minigame.platform.room.application.RoomApplicationService.RoomSnapshotView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

public final class RoomWebDtos {
    private RoomWebDtos() {
    }

    public record CreateRoomRequest(
            @NotBlank @Size(max = 24) String title,
            @NotNull Visibility visibility,
            @Size(max = 20) String password,
            @NotNull GameType gameType
    ) {
    }

    public record JoinRoomRequest(@Size(max = 20) String password) {
    }

    public record ParticipantResponse(
            String actorId,
            String nickname,
            boolean ready,
            boolean spectator
    ) {
    }

    public record RoomSnapshotResponse(
            String roomId,
            String code,
            String title,
            Visibility visibility,
            GameType gameType,
            RoomStatus status,
            boolean canStart,
            boolean passwordProtected,
            int participantCount,
            int maxParticipants,
            String hostId,
            long sequence,
            int rounds,
            int actionSeconds,
            int discussionSeconds,
            String categoryPack,
            List<ParticipantResponse> participants,
            List<ChatMessageResponse> chats,
            GameSnapshotResponse game
    ) {
    }

    public record GameSnapshotResponse(Object publicState, Object privateState) {
    }

    public record ChatMessageResponse(
            UUID messageId,
            String actorId,
            String nickname,
            String body,
            Instant sentAt
    ) {
    }

    public record LobbyRoomResponse(
            String roomId,
            String code,
            String title,
            GameType gameType,
            RoomStatus status,
            boolean passwordProtected,
            int participantCount,
            int maxParticipants,
            String hostNickname,
            long sequence
    ) {
    }

    static RoomSnapshotResponse from(RoomSnapshotView view) {
        return new RoomSnapshotResponse(
                view.roomId(),
                view.code(),
                view.title(),
                view.visibility(),
                view.gameType(),
                view.status(),
                view.canStart(),
                view.passwordProtected(),
                view.participantCount(),
                view.maxParticipants(),
                view.hostId(),
                view.sequence(),
                view.rounds(),
                view.actionSeconds(),
                view.discussionSeconds(),
                view.categoryPack(),
                view.participants().stream().map(RoomWebDtos::from).toList(),
                view.chats().stream().map(message -> new ChatMessageResponse(
                        message.messageId(),
                        message.actorId(),
                        message.nickname(),
                        message.body(),
                        message.sentAt()
                )).toList(),
                view.game() == null ? null : new GameSnapshotResponse(
                        view.game().publicState(),
                        view.game().privateState()
                )
        );
    }

    static LobbyRoomResponse from(LobbyRoomView view) {
        return new LobbyRoomResponse(
                view.roomId(),
                view.code(),
                view.title(),
                view.gameType(),
                view.status(),
                view.passwordProtected(),
                view.participantCount(),
                view.maxParticipants(),
                view.hostNickname(),
                view.sequence()
        );
    }

    private static ParticipantResponse from(ParticipantView view) {
        return new ParticipantResponse(view.actorId(), view.nickname(), view.ready(), view.spectator());
    }
}
