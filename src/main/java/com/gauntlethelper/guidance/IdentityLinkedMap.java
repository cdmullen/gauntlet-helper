/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2026, cdmullen
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.gauntlethelper.guidance;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * A map keyed by identity, like IdentityHashMap, that iterates in insertion order. Guidance.objects uses it so that
 * equal-cost ties between scene objects break the same way on every run (IdentityHashMap's order follows identity
 * hash codes, so a tie could pick a different door on each run).
 */
final class IdentityLinkedMap<K, V> extends AbstractMap<K, V>
{
	private static final class Key
	{
		final Object value;

		Key(Object value)
		{
			this.value = value;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Key && ((Key) other).value == value;
		}

		@Override
		public int hashCode()
		{
			return System.identityHashCode(value);
		}
	}

	private final LinkedHashMap<Key, V> map = new LinkedHashMap<>();

	@Override
	public V get(Object key) { return map.get(new Key(key)); }

	@Override
	public boolean containsKey(Object key) { return map.containsKey(new Key(key)); }

	@Override
	public V put(K key, V value) { return map.put(new Key(key), value); }

	@Override
	public V remove(Object key) { return map.remove(new Key(key)); }

	@Override
	public void clear() { map.clear(); }

	@Override
	public int size() { return map.size(); }

	@Nonnull
	@Override
	public Set<Map.Entry<K, V>> entrySet()
	{
		return new AbstractSet<>()
		{
			@Override
			public int size() { return map.size(); }

			@Nonnull
			@Override
			public Iterator<Map.Entry<K, V>> iterator()
			{
				Iterator<Map.Entry<Key, V>> entries = map.entrySet().iterator();
				return new Iterator<>()
				{
					@Override
					public boolean hasNext() { return entries.hasNext(); }

					@Override
					@SuppressWarnings("unchecked")
					public Map.Entry<K, V> next()
					{
						Map.Entry<Key, V> entry = entries.next();
						return new AbstractMap.SimpleImmutableEntry<>((K) entry.getKey().value, entry.getValue());
					}

					@Override
					public void remove() { entries.remove(); }
				};
			}
		};
	}
}
