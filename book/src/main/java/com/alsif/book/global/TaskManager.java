package com.alsif.book.global;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class TaskManager {

	private final Map<Long, Boolean> task = new ConcurrentHashMap<>();

	public synchronized boolean tryAcquire(List<Long> seqs) {
		boolean hasLockedSeat = seqs.stream()
			.anyMatch(seq -> Boolean.TRUE.equals(task.get(seq)));
		if (hasLockedSeat) {
			return false;
		}
		seqs.forEach(seq -> task.put(seq, true));
		return true;
	}

	public void release(List<Long> seqs) {
		seqs.forEach(task::remove);
	}
}
