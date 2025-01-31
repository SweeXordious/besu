/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.hyperledger.besu.ethereum.api.query;

import static org.hyperledger.besu.ethereum.api.query.BlockchainQueries.BLOCKS_PER_BLOOM_CACHE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.Log;
import org.hyperledger.besu.ethereum.core.LogsBloomFilter;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.util.bytes.BytesValue;
import org.hyperledger.besu.util.uint.UInt256;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class BlockchainQueriesLogCacheTest {

  @ClassRule public static TemporaryFolder cacheDir = new TemporaryFolder();

  private static LogsQuery logsQuery;
  private Hash testHash;
  private static LogsBloomFilter testLogsBloomFilter;

  @Mock Blockchain blockchain;
  @Mock WorldStateArchive worldStateArchive;

  @BeforeClass
  public static void setupClass() throws IOException {
    final Address testAddress = Address.fromHexString("0x123456");
    final BytesValue testMessage = BytesValue.fromHexString("0x9876");
    final Log testLog = new Log(testAddress, testMessage, List.of());
    testLogsBloomFilter = new LogsBloomFilter();
    testLogsBloomFilter.insertLog(testLog);
    logsQuery = new LogsQuery(List.of(testAddress), List.of());

    for (int i = 0; i < 2; i++) {
      final RandomAccessFile file =
          new RandomAccessFile(cacheDir.newFile("logBloom-" + i + ".index"), "rws");
      writeThreeEntries(testLogsBloomFilter, file);
      file.seek((BLOCKS_PER_BLOOM_CACHE - 3) * LogsBloomFilter.BYTE_SIZE);
      writeThreeEntries(testLogsBloomFilter, file);
    }
  }

  private static void writeThreeEntries(final LogsBloomFilter filter, final RandomAccessFile file)
      throws IOException {
    file.write(filter.getByteArray());
    file.write(filter.getByteArray());
    file.write(filter.getByteArray());
  }

  @Before
  public void setup() {
    final BlockHeader fakeHeader =
        new BlockHeader(
            Hash.EMPTY,
            Hash.EMPTY,
            Address.ZERO,
            Hash.EMPTY,
            Hash.EMPTY,
            Hash.EMPTY,
            testLogsBloomFilter,
            UInt256.ZERO,
            0,
            0,
            0,
            0,
            BytesValue.EMPTY,
            Hash.EMPTY,
            0,
            new MainnetBlockHeaderFunctions());
    testHash = fakeHeader.getHash();
    final BlockBody fakeBody = new BlockBody(Collections.emptyList(), Collections.emptyList());
    when(blockchain.getBlockHashByNumber(anyLong())).thenReturn(Optional.of(testHash));
    when(blockchain.getBlockHeader(any())).thenReturn(Optional.of(fakeHeader));
    when(blockchain.getBlockHeader(anyLong())).thenReturn(Optional.of(fakeHeader));
    when(blockchain.getTxReceipts(any())).thenReturn(Optional.of(Collections.emptyList()));
    when(blockchain.getBlockBody(any())).thenReturn(Optional.of(fakeBody));
  }

  /**
   * Tests fours sets of a three block range where the seam (where the segment changes) is in all
   * possible positions in the range.
   *
   * <p>For this test both sides of the seam are cached.
   */
  @Test
  public void cachedCachedSeamTest() {
    final BlockchainQueries query =
        new BlockchainQueries(
            blockchain, worldStateArchive, Optional.of(cacheDir.getRoot().toPath()));

    for (long i = BLOCKS_PER_BLOOM_CACHE - 3; i <= BLOCKS_PER_BLOOM_CACHE; i++) {
      query.matchingLogs(i, i + 2, logsQuery);
    }

    // 4 ranges of 3 hits a piece = 12 calls - 97-99, 98-00, 99-01, 00-02
    verify(blockchain, times(12)).getBlockHashByNumber(anyLong());
    verify(blockchain, times(12)).getBlockHeader(testHash);
    verify(blockchain, times(12)).getTxReceipts(testHash);
    verify(blockchain, times(12)).getBlockBody(testHash);
    verify(blockchain, times(12)).blockIsOnCanonicalChain(testHash);

    verifyNoMoreInteractions(blockchain);
  }

  /**
   * Tests fours sets of a three block range where the seam (where the segment changes) is in all
   * possible positions in the range.
   *
   * <p>For this test the low side is cached the high side is uncached.
   */
  @Test
  public void cachedUncachedSeamTest() {
    final BlockchainQueries query =
        new BlockchainQueries(
            blockchain, worldStateArchive, Optional.of(cacheDir.getRoot().toPath()));

    for (long i = (2 * BLOCKS_PER_BLOOM_CACHE) - 3; i <= 2 * BLOCKS_PER_BLOOM_CACHE; i++) {
      query.matchingLogs(i, i + 2, logsQuery);
    }

    // 6 sets of calls on cache side of seam: 97-99, 98-99, 99, {}
    verify(blockchain, times(6)).getBlockHashByNumber(anyLong());

    // 6 sets of calls on uncached side of seam: {}, 00, 00-01, 00-02
    verify(blockchain, times(6)).getBlockHeader(anyLong());

    // called on both halves of the seam
    verify(blockchain, times(12)).getBlockHeader(testHash);
    verify(blockchain, times(12)).getTxReceipts(testHash);
    verify(blockchain, times(12)).getBlockBody(testHash);
    verify(blockchain, times(12)).blockIsOnCanonicalChain(testHash);

    verifyNoMoreInteractions(blockchain);
  }

  /**
   * Tests fours sets of a three block range where the seam (where the segment changes) is in all
   * possible positions in the range.
   *
   * <p>For this test the both sides are uncached.
   */
  @Test
  public void uncachedUncachedSeamTest() {
    final BlockchainQueries query =
        new BlockchainQueries(
            blockchain, worldStateArchive, Optional.of(cacheDir.getRoot().toPath()));

    for (long i = (3 * BLOCKS_PER_BLOOM_CACHE) - 3; i <= 3 * BLOCKS_PER_BLOOM_CACHE; i++) {
      query.matchingLogs(i, i + 2, logsQuery);
    }

    // 4 ranges of 3 hits a piece = 12 calls - 97-99, 98-00, 99-01, 00-02
    verify(blockchain, times(12)).getBlockHeader(anyLong());
    verify(blockchain, times(12)).getBlockHeader(testHash);
    verify(blockchain, times(12)).getTxReceipts(testHash);
    verify(blockchain, times(12)).getBlockBody(testHash);
    verify(blockchain, times(12)).blockIsOnCanonicalChain(testHash);

    verifyNoMoreInteractions(blockchain);
  }
}
