package com.minigame.platform.game.adapter.out.persistence;

import com.minigame.platform.game.application.LiarContentPort;
import com.minigame.platform.game.domain.liar.LiarWord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class JpaLiarContentAdapter implements LiarContentPort {
    private static final String ALL_CATEGORY_CODE = "all";

    private final ObjectProvider<EntityManager> entityManagers;

    public JpaLiarContentAdapter(ObjectProvider<EntityManager> entityManagers) {
        this.entityManagers = entityManagers;
    }

    @Override
    public boolean available(String categoryCode, Set<UUID> excludedIds, int required) {
        return candidates(categoryCode, excludedIds).getResultList().size() >= required;
    }

    @Override
    public List<LiarWord> select(String categoryCode, Set<UUID> excludedIds, int limit) {
        var query = candidates(categoryCode, excludedIds);
        query.setMaxResults(limit);
        return query.getResultList().stream()
                .map(item -> new LiarWord(
                        item.getId(),
                        item.getContentPack().getCode(),
                        item.getValue(),
                        Set.copyOf(Arrays.asList(item.getAliases()))
                ))
                .toList();
    }

    private TypedQuery<ContentItemEntity> candidates(String categoryCode, Set<UUID> excludedIds) {
        var jpql = new StringBuilder("""
                select item from ContentItemEntity item
                join item.contentPack pack
                where pack.gameType = 'LIAR'
                  and pack.active = true
                  and item.active = true
                """);
        if (!ALL_CATEGORY_CODE.equals(categoryCode)) {
            jpql.append(" and pack.code = :categoryCode");
        }
        if (!excludedIds.isEmpty()) {
            jpql.append(" and item.id not in :excludedIds");
        }
        jpql.append(" order by item.id");
        var query = entityManagers.getObject().createQuery(jpql.toString(), ContentItemEntity.class);
        if (!ALL_CATEGORY_CODE.equals(categoryCode)) {
            query.setParameter("categoryCode", categoryCode);
        }
        if (!excludedIds.isEmpty()) {
            query.setParameter("excludedIds", excludedIds);
        }
        return query;
    }
}
