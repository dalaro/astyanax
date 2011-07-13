package com.netflix.astyanax.thrift;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.cassandra.dht.RandomPartitioner;
import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.CounterColumn;
import org.apache.cassandra.thrift.CounterSuperColumn;
import org.apache.cassandra.thrift.CqlResult;
import org.apache.cassandra.thrift.KeyRange;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.cassandra.thrift.Cassandra.Client;
import org.apache.commons.lang.NotImplementedException;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.netflix.astyanax.CounterMutation;
import com.netflix.astyanax.Serializer;
import com.netflix.astyanax.KeyspaceTracers;
import com.netflix.astyanax.Query;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.connectionpool.ConnectionPool;
import com.netflix.astyanax.connectionpool.ConnectionPoolConfiguration;
import com.netflix.astyanax.connectionpool.FutureOperationResult;
import com.netflix.astyanax.connectionpool.NodeDiscovery;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.connectionpool.impl.TokenRangeImpl;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.model.ColumnPath;
import com.netflix.astyanax.model.KeySlice;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.model.Rows;
import com.netflix.astyanax.model.TokenRange;
import com.netflix.astyanax.query.ColumnFamilyQuery;
import com.netflix.astyanax.shallows.EmptyRowsImpl;

public final class ThriftKeyspaceImpl implements Keyspace {

	private final ConnectionPool<Cassandra.Client> connectionPool;
	private final RandomPartitioner partitioner;
	private final NodeDiscovery discovery;
	private final ConnectionPoolConfiguration config;
	private final KeyspaceTracers tracers;
	
	public ThriftKeyspaceImpl(ConnectionPoolConfiguration config) {
		this.config = config;
		this.connectionPool = config
			.getConnectionPoolFactory()
				.createConnectionPool(config, 
					new ThriftConnectionFactoryImpl(config));
		this.partitioner = new RandomPartitioner();
		this.discovery = config
			.getNodeDiscoveryFactory().
				createNodeDiscovery(config, this, connectionPool);
		this.tracers = config.getKeyspaceTracers();
	}
	
	@Override
	public String getKeyspaceName() {
		return this.config.getKeyspaceName();
	}

	@Override
	public void start() {
		this.connectionPool.start();
		this.discovery.start();
	}
	
	@Override
	public void shutdown() {
		this.discovery.shutdown();
		this.connectionPool.shutdown();
	}

	@Override
	public <K,C> Query<K, C, ColumnList<C>> prepareGetRowQuery(
			final ColumnFamily<K, ?> columnFamily, 
			final Serializer<C> columnSerializer, 
			final K rowKey) {
		return new AbstractQueryImpl<K, C, ColumnList<C>> (null, config.getDefaultReadConsistencyLevel(), config.getSocketTimeout()) {
			@Override
			public OperationResult<ColumnList<C>> execute() throws ConnectionException {
				OperationResult<ColumnList<C>> result = 
					connectionPool.executeWithFailover(new AbstractKeyspaceOperationImpl<ColumnList<C>>(getKeyspaceName()) {
					@Override
					public ColumnList<C> execute(Cassandra.Client client) throws ConnectionException {
						try {
							List<ColumnOrSuperColumn> columnList =
								client.get_slice(columnFamily.getKeySerializer().toByteBuffer(rowKey), 
									ThriftConverter.getColumnParent(columnFamily, path),
									ThriftConverter.getPredicate(slice, columnSerializer),
									ThriftConverter.ToThriftConsistencyLevel(consistencyLevel));
							
							return new ThriftColumnOrSuperColumnListImpl<C>(columnList, columnSerializer);
						} 
						catch (Exception e) {
							throw ThriftConverter.ToConnectionPoolException(e);
						}
					}

					@Override
					public BigInteger getKey() {
						return partitioner.getToken(columnFamily.getKeySerializer().toByteBuffer(rowKey)).token;
					}
				});
				tracers.incRowQuery(ThriftKeyspaceImpl.this, result.getHost(), result.getLatency());
				return result;
			}

			@Override
			public FutureOperationResult<ColumnList<C>> executeAsync() {
				throw new NotImplementedException();
			}
		};
	}

	@Override
	public <K,C> Query<K, C, Rows<K, C>> prepareGetMultiRowQuery(
			final ColumnFamily<K, ?> columnFamily, 
			final Serializer<C> columnSerializer, 
			final KeySlice<K> keys) {
        Preconditions.checkArgument(columnFamily != null, "CF must not be null");
        Preconditions.checkArgument(keys != null, "Keys must not be null");
        
		return new AbstractQueryImpl<K, C, Rows<K, C>> (null, config.getDefaultReadConsistencyLevel(), config.getSocketTimeout()) {

			@Override
			public OperationResult<Rows<K, C>> execute() throws ConnectionException {
				OperationResult<Rows<K, C>> result =
				connectionPool.executeWithFailover(new AbstractKeyspaceOperationImpl<Rows<K, C>>(getKeyspaceName()) {
					@Override
					public Rows<K, C> execute(Cassandra.Client client) throws ConnectionException {
						try {
							if (keys.getKeys() != null) {
								List<ByteBuffer> bbKeys = new ArrayList<ByteBuffer>();
								for (K k : keys.getKeys()) {
									bbKeys.add(columnFamily.getKeySerializer().toByteBuffer(k));
								}
								
								// Map of row key to Slice or Super slice
								Map<ByteBuffer, List<ColumnOrSuperColumn>> cfmap;
								cfmap = client.multiget_slice(
										bbKeys, 
										ThriftConverter.getColumnParent(columnFamily, path),
										ThriftConverter.getPredicate(slice, columnSerializer),
										ThriftConverter.ToThriftConsistencyLevel(consistencyLevel));
								
								if (cfmap == null) {
									return new EmptyRowsImpl<K,C>();
								}
								else {
									return new ThriftRowsListImpl<K, C>(cfmap, columnFamily.getKeySerializer(), columnSerializer);
								}
							} else {
								// This is sorted list
								// Same call for standard and super columns via the ColumnParent
								KeyRange range = new KeyRange();
								if (keys.getStartKey() != null) 
									range.setStart_key(columnFamily.getKeySerializer().toByteBuffer(keys.getStartKey()));
								if (keys.getEndKey() != null)
									range.setEnd_key(columnFamily.getKeySerializer().toByteBuffer(keys.getEndKey()));   
								range.setCount(keys.getLimit());
								range.setStart_token(keys.getStartToken());
								range.setEnd_token(keys.getEndToken());
								
								List<org.apache.cassandra.thrift.KeySlice> keySlices;
								keySlices = client.get_range_slices(
										ThriftConverter.getColumnParent(columnFamily, path),
										ThriftConverter.getPredicate(slice, columnSerializer),
										range, 
										ThriftConverter.ToThriftConsistencyLevel(consistencyLevel));
								if (keySlices == null || keySlices.isEmpty()) {
									return new EmptyRowsImpl<K,C>();
								}
								else {
									return new ThriftRowsSliceImpl<K, C>(keySlices, columnFamily.getKeySerializer(), columnSerializer);
								}
							}
						}
						catch (Exception e) {
							throw ThriftConverter.ToConnectionPoolException(e);
						}
					}
				});
				tracers.incMultiRowQuery(ThriftKeyspaceImpl.this, result.getHost(), result.getLatency());
				return result;
			}

			@Override
			public FutureOperationResult<Rows<K, C>> executeAsync() {
				throw new NotImplementedException();
			}
		};
	}

	@Override
	public <K, C> Query<K, C, Column<C>> prepareGetColumnQuery(
			final ColumnFamily<K, ?> columnFamily, final K key, ColumnPath<C> path) {
		
        Preconditions.checkArgument(columnFamily != null, "ColumnFamily must not be null");
        Preconditions.checkArgument(key != null, "Key must not be null");
        Preconditions.checkArgument(path != null, "Path must not be null");

		return new AbstractQueryImpl<K, C, Column<C>> (path, config.getDefaultReadConsistencyLevel(), config.getSocketTimeout()) {
			@Override
			public OperationResult<Column<C>> execute() throws ConnectionException {
				OperationResult<Column<C>> result = 
				connectionPool.executeWithFailover(new AbstractKeyspaceOperationImpl<Column<C>>(getKeyspaceName()) {
					@Override
					public Column<C> execute(Cassandra.Client client) throws ConnectionException {
						try {
							// use for column and super column
							ColumnOrSuperColumn column =
								client.get(columnFamily.getKeySerializer().toByteBuffer(key),
									ThriftConverter.getColumnPath(columnFamily, path),
									ThriftConverter.ToThriftConsistencyLevel(consistencyLevel));
							if (column.isSetColumn()) {
								org.apache.cassandra.thrift.Column c = column.getColumn();
								return new ThriftColumnImpl<C>(path.getSerializer().fromBytes(c.getName()), c.getValue());
							}
							else if (column.isSetSuper_column()) {
								SuperColumn sc = column.getSuper_column();
								return new ThriftSuperColumnImpl<C>(path.getSerializer().fromBytes(sc.getName()), sc.getColumns());
							}
							else if (column.isSetCounter_column()) {
								org.apache.cassandra.thrift.CounterColumn c = column.getCounter_column();
								return new ThriftCounterColumnImpl<C>(path.getSerializer().fromBytes(c.getName()), c.getValue());
							}
							else if (column.isSetCounter_super_column()) {
								CounterSuperColumn sc = column.getCounter_super_column();
								return new ThriftCounterSuperColumnImpl<C>(path.getSerializer().fromBytes(sc.getName()), sc.getColumns());
							}
							else {
								throw new RuntimeException("Unknown column type in response");
							}
						} 
						catch (Exception e) {
							throw ThriftConverter.ToConnectionPoolException(e);
						}
					}

					@Override
					public BigInteger getKey() {
						return partitioner.getToken(columnFamily.getKeySerializer().toByteBuffer(key)).token;
					}
				});
				tracers.incRowQuery(ThriftKeyspaceImpl.this, result.getHost(), result.getLatency());
				return result;
			}

			@Override
			public FutureOperationResult<Column<C>> executeAsync() {
				throw new NotImplementedException();
			}

		};
	}	
	
	@Override
	public MutationBatch prepareMutationBatch() {
		return new AbstractThriftMutationBatchImpl(config.getClock(), config.getDefaultWriteConsistencyLevel(), config.getSocketTimeout()) {
			@Override
			public OperationResult<Void> execute() throws ConnectionException {
				OperationResult<Void> result = 
				connectionPool.executeWithFailover(new AbstractKeyspaceOperationImpl<Void>(getKeyspaceName()) {
					@Override
					public Void execute(Client client) throws ConnectionException {
						try {
							client.batch_mutate(getMutationMap(), ThriftConverter.ToThriftConsistencyLevel(consistencyLevel));
							discardMutations();
							return null;
						} catch (Exception e) {
							throw ThriftConverter.ToConnectionPoolException(e);
						}
					}

					@Override
					public BigInteger getKey() {
						// We provide a token iff there is only one row key in the map
						// otherwise it's pointless to be token aware
						if (getMutationMap().size() == 1)
							return partitioner.getToken(getMutationMap().keySet().iterator().next()).token;
						return null;
					}
				});
				tracers.incMutation(ThriftKeyspaceImpl.this, result.getHost(), result.getLatency());
				return result;
			}

			@Override
			public FutureOperationResult<Void> executeAsync()
					throws ConnectionException {
				throw new NotImplementedException();
			}
		};
	}
	
	@Override
	public List<TokenRange> describeRing() throws ConnectionException {
		OperationResult<List<TokenRange>> result = 
			connectionPool.executeWithFailover(new AbstractKeyspaceOperationImpl<List<TokenRange>>(getKeyspaceName()) {
			@Override
			public List<TokenRange> execute(Cassandra.Client client) throws ConnectionException {
				try {
					List<org.apache.cassandra.thrift.TokenRange> tokenRanges = 
						client.describe_ring(getKeyspaceName());
					return Lists.transform(tokenRanges, 
							new Function<org.apache.cassandra.thrift.TokenRange, TokenRange>() {
						@Override
						public TokenRange apply(
								org.apache.cassandra.thrift.TokenRange tr) {
							return new TokenRangeImpl(tr.getStart_token(), tr.getEnd_token(), tr.getEndpoints());
						}
						
					});
				} 
				catch (Exception e) {
					throw ThriftConverter.ToConnectionPoolException(e);
				}
			}
		});
		
		return result.getResult();
	}

	@Override
	public <K, C> CounterMutation<K, C> prepareCounterMutation(
			final ColumnFamily<K, C> columnFamily, final K rowKey, final ColumnPath<C> path,
			final long amount) {
		return new AbstractCounterMutationImpl<K,C>(config.getDefaultWriteConsistencyLevel(), config.getSocketTimeout()) {

			@Override
			public OperationResult<Void> execute() throws ConnectionException {
				OperationResult<Void> result = 
					connectionPool.executeWithFailover(new AbstractKeyspaceOperationImpl<Void>(getKeyspaceName()) {
						@Override
						public Void execute(Client client) throws ConnectionException {
							try {
								CounterColumn column = new CounterColumn();
								column.setValue(amount);
								column.setName(path.getLast());
								
								client.add(columnFamily.getKeySerializer().toByteBuffer(rowKey),
										   ThriftConverter.getColumnParent(columnFamily, path),
										   column, 
										   ThriftConverter.ToThriftConsistencyLevel(consistencyLevel));
							} catch (Exception e) {
								throw ThriftConverter.ToConnectionPoolException(e);
							}
							return null;
						}
					});
					tracers.incMutation(ThriftKeyspaceImpl.this, result.getHost(), result.getLatency());
					return result;
				}
		};
	}

	@Override
	public <K, C> Query<K, C, Rows<K, C>> prepareCqlQuery(String cql) {
        Preconditions.checkArgument(cql != null, "CQL must not be null");
        
		return new AbstractQueryImpl<K, C, Rows<K, C>> (null, config.getDefaultReadConsistencyLevel(), config.getSocketTimeout()) {

			@Override
			public OperationResult<Rows<K, C>> execute() throws ConnectionException {
				OperationResult<Rows<K, C>> result =
				connectionPool.executeWithFailover(new AbstractKeyspaceOperationImpl<Rows<K, C>>(getKeyspaceName()) {
					@Override
					public Rows<K, C> execute(Cassandra.Client client) throws ConnectionException {
						try {
							CqlResult cfmap;
							cfmap = client.execute_cql_query(null, null);
							return null;
							/*
							if (cfmap == null) {
								return new EmptyRowsImpl<K,C>();
							}
							else if (path != null && path.getSerializer() != null) {
								return new ThriftRowsSliceImpl<K, C>(cfmap, columnFamily.getKeySerializer(), path.getSerializer());
							}
							else {
								return new ThriftRowsSliceImpl<K, C>(cfmap, columnFamily.getKeySerializer(), columnFamily.getColumnSerializer());
							}
							*/
						}
						catch (Exception e) {
							throw ThriftConverter.ToConnectionPoolException(e);
						}
					}
				});
				tracers.incMultiRowQuery(ThriftKeyspaceImpl.this, result.getHost(), result.getLatency());
				return result;
			}

			@Override
			public FutureOperationResult<Rows<K, C>> executeAsync() {
				throw new NotImplementedException();
			}
		};
	}

	@Override
	public <K, C> ColumnFamilyQuery<K,C> prepareQuery(ColumnFamily<K, C> cf) {
		return new ThriftColumnFamilyQueryImpl<K,C>(connectionPool, this.getKeyspaceName(), cf, this.config.getDefaultReadConsistencyLevel());
	}
}
