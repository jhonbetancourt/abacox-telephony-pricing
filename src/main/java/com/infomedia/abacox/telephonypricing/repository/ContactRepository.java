// src/main/java/com/infomedia/abacox/telephonypricing/repository/ContactRepository.java

package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.db.entity.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Set;

public interface ContactRepository extends JpaRepository<Contact, Long>, JpaSpecificationExecutor<Contact> {
    
    /**
     * Finds all Contact entities where the phone number is in the provided set.
     * @param phoneNumbers A set of phone numbers to search for.
     * @return A list of matching Contact entities.
     */
    List<Contact> findByPhoneNumberIn(Set<String> phoneNumbers);

}