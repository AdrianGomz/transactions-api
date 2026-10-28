package com.spin.transactions.infrastructure.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.spin.transactions.application.port.TransactionPage;
import com.spin.transactions.application.port.TransactionQuery;
import com.spin.transactions.application.port.TransactionRepository;
import com.spin.transactions.application.port.TransactionUpdateConflictException;
import com.spin.transactions.domain.Transaction;
import com.spin.transactions.domain.TransactionStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class JpaTransactionRepository implements TransactionRepository {

	private final EntityManager entityManager;

	public JpaTransactionRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override
	public Transaction insertPending(Transaction transaction) {
		if (transaction.status() != TransactionStatus.PENDING) {
			throw new IllegalArgumentException("Only PENDING transactions may be inserted.");
		}
		var entity = TransactionEntity.fromDomain(transaction);
		entityManager.persist(entity);
		entityManager.flush();
		return entity.toDomain();
	}

	@Override
	public Transaction saveOutcome(Transaction transaction) {
		if (transaction.status() == TransactionStatus.PENDING) {
			throw new IllegalArgumentException("An outcome must be EXECUTED, REJECTED, or UNKNOWN.");
		}
		var details = transaction.details();
		// One conditional update arbitrates competing writers without holding a lock during provider I/O.
		int updated = entityManager.createQuery("""
				UPDATE StoredTransaction t
				SET t.status = :status, t.providerTransactionId = :providerId,
				    t.balanceAfter = :balance, t.providerExecutedAt = :executedAt,
				    t.rejection = :rejection, t.failure = :failure, t.updatedAt = :updatedAt
				WHERE t.id = :id AND t.status = :pending
				  AND t.accountId = :accountId AND t.type = :type AND t.amount = :amount
				  AND t.currency = :currency AND t.createdAt = :createdAt
				  AND (t.description = :description OR (t.description IS NULL AND :description IS NULL))
				""")
				.setParameter("status", transaction.status())
				.setParameter("providerId", transaction.providerTransactionId())
				.setParameter("balance", transaction.balanceAfter())
				.setParameter("executedAt", TransactionEntity.toDatabasePrecision(transaction.providerExecutedAt()))
				.setParameter("rejection", transaction.rejection())
				.setParameter("failure", transaction.failure())
				.setParameter("updatedAt", TransactionEntity.toDatabasePrecision(transaction.updatedAt()))
				.setParameter("id", transaction.id())
				.setParameter("pending", TransactionStatus.PENDING)
				.setParameter("accountId", details.accountId())
				.setParameter("type", details.type())
				.setParameter("amount", details.amount())
				.setParameter("currency", details.currency())
				.setParameter("createdAt", TransactionEntity.toDatabasePrecision(transaction.createdAt()))
				.setParameter("description", details.description())
				.executeUpdate();
		if (updated != 1) {
			throw new TransactionUpdateConflictException(transaction.id());
		}
		return entityManager.find(TransactionEntity.class, transaction.id()).toDomain();
	}

	@Override
	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public Optional<Transaction> findById(UUID id) {
		return Optional.ofNullable(entityManager.find(TransactionEntity.class, Objects.requireNonNull(id)))
				.map(TransactionEntity::toDomain);
	}

	@Override
	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public TransactionPage findAll(TransactionQuery query) {
		var sql = new StringBuilder("SELECT * FROM transactions WHERE 1 = 1");
		var parameters = new LinkedHashMap<String, Object>();
		if (query.accountId() != null) {
			sql.append(" AND account_id = :accountId");
			parameters.put("accountId", query.accountId());
		}
		if (query.status() != null) {
			sql.append(" AND status = :status");
			parameters.put("status", query.status().name());
		}
		if (query.type() != null) {
			sql.append(" AND type = :type");
			parameters.put("type", query.type().name());
		}
		// Native OFFSET accepts a long; JPA's setFirstResult only accepts an int.
		sql.append(" ORDER BY created_at DESC, id DESC LIMIT :fetchLimit OFFSET :offset");
		var nativeQuery = entityManager.createNativeQuery(sql.toString(), TransactionEntity.class);
		parameters.forEach(nativeQuery::setParameter);
		nativeQuery.setParameter("fetchLimit", query.limit() + 1);
		nativeQuery.setParameter("offset", query.offset());
		List<?> rows = nativeQuery.getResultList();
		var items = rows.stream().limit(query.limit()).map(TransactionEntity.class::cast)
				.map(TransactionEntity::toDomain).toList();
		return new TransactionPage(items, query.page(), query.limit(), rows.size() > query.limit());
	}

}
