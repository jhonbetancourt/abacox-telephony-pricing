package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class CommunicationLocationLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<CommunicationLocation> findById(Long commLocationId) {
        try {
            CommunicationLocation cl = entityManager.find(CommunicationLocation.class, commLocationId);
            return Optional.ofNullable(cl);
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
}
