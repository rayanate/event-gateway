package com.charlesschwab.eventGateway.repository;

import com.charlesschwab.eventGateway.model.EventRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EventRecordRepository extends JpaRepository<EventRecord, String> {

	List<EventRecord> findByAccountIdOrderByEventTimestampDesc(String accountId);

	List<EventRecord> findAllByOrderByEventTimestampDesc();

}

