/**
 * Autogenerated by Thrift
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 */
package com.foursquare.twofishes;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Set;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.Collections;
import java.util.BitSet;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.thrift.*;
import org.apache.thrift.async.*;
import org.apache.thrift.meta_data.*;
import org.apache.thrift.transport.*;
import org.apache.thrift.protocol.*;

// No additional import required for struct/union.

public class CellGeometry implements TBase<CellGeometry, CellGeometry._Fields>, java.io.Serializable, Cloneable {
  private static final TStruct STRUCT_DESC = new TStruct("CellGeometry");

  private static final TField OID_FIELD_DESC = new TField("oid", TType.STRING, (short)1);
  private static final TField WKB_GEOMETRY_FIELD_DESC = new TField("wkbGeometry", TType.STRING, (short)2);
  private static final TField WOE_TYPE_FIELD_DESC = new TField("woeType", TType.I32, (short)3);
  private static final TField FULL_FIELD_DESC = new TField("full", TType.BOOL, (short)4);

  public ByteBuffer oid;
  public ByteBuffer wkbGeometry;
  /**
   * 
   * @see com.foursquare.twofishes.YahooWoeType
   */
  public com.foursquare.twofishes.YahooWoeType woeType;
  public boolean full;

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements TFieldIdEnum {
    OID((short)1, "oid"),
    WKB_GEOMETRY((short)2, "wkbGeometry"),
    /**
     * 
     * @see com.foursquare.twofishes.YahooWoeType
     */
    WOE_TYPE((short)3, "woeType"),
    FULL((short)4, "full");

    private static final Map<String, _Fields> byName = new HashMap<String, _Fields>();

    static {
      for (_Fields field : EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // OID
          return OID;
        case 2: // WKB_GEOMETRY
          return WKB_GEOMETRY;
        case 3: // WOE_TYPE
          return WOE_TYPE;
        case 4: // FULL
          return FULL;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    public static _Fields findByName(String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final String _fieldName;

    _Fields(short thriftId, String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final int __FULL_ISSET_ID = 0;
  private BitSet __isset_bit_vector = new BitSet(1);

  public static final Map<_Fields, FieldMetaData> metaDataMap;
  static {
    Map<_Fields, FieldMetaData> tmpMap = new EnumMap<_Fields, FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.OID, new FieldMetaData("oid", TFieldRequirementType.OPTIONAL, 
        new FieldValueMetaData(TType.STRING)));
    tmpMap.put(_Fields.WKB_GEOMETRY, new FieldMetaData("wkbGeometry", TFieldRequirementType.OPTIONAL, 
        new FieldValueMetaData(TType.STRING)));
    tmpMap.put(_Fields.WOE_TYPE, new FieldMetaData("woeType", TFieldRequirementType.OPTIONAL, 
        new EnumMetaData(TType.ENUM, com.foursquare.twofishes.YahooWoeType.class)));
    tmpMap.put(_Fields.FULL, new FieldMetaData("full", TFieldRequirementType.OPTIONAL, 
        new FieldValueMetaData(TType.BOOL)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    FieldMetaData.addStructMetaDataMap(CellGeometry.class, metaDataMap);
  }

  public CellGeometry() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public CellGeometry(CellGeometry other) {
    __isset_bit_vector.clear();
    __isset_bit_vector.or(other.__isset_bit_vector);
    if (other.isSetOid()) {
      this.oid = TBaseHelper.copyBinary(other.oid);
;
    }
    if (other.isSetWkbGeometry()) {
      this.wkbGeometry = TBaseHelper.copyBinary(other.wkbGeometry);
;
    }
    if (other.isSetWoeType()) {
      this.woeType = other.woeType;
    }
    this.full = other.full;
  }

  public CellGeometry deepCopy() {
    return new CellGeometry(this);
  }

  @Override
  public void clear() {
    this.oid = null;
    this.wkbGeometry = null;
    this.woeType = null;
    setFullIsSet(false);
    this.full = false;
  }

  public byte[] getOid() {
    setOid(TBaseHelper.rightSize(oid));
    return oid.array();
  }

  public ByteBuffer BufferForOid() {
    return oid;
  }

  public CellGeometry setOid(byte[] oid) {
    setOid(ByteBuffer.wrap(oid));
    return this;
  }

  public CellGeometry setOid(ByteBuffer oid) {
    this.oid = oid;
    return this;
  }

  public void unsetOid() {
    this.oid = null;
  }

  /** Returns true if field oid is set (has been asigned a value) and false otherwise */
  public boolean isSetOid() {
    return this.oid != null;
  }

  public void setOidIsSet(boolean value) {
    if (!value) {
      this.oid = null;
    }
  }

  public byte[] getWkbGeometry() {
    setWkbGeometry(TBaseHelper.rightSize(wkbGeometry));
    return wkbGeometry.array();
  }

  public ByteBuffer BufferForWkbGeometry() {
    return wkbGeometry;
  }

  public CellGeometry setWkbGeometry(byte[] wkbGeometry) {
    setWkbGeometry(ByteBuffer.wrap(wkbGeometry));
    return this;
  }

  public CellGeometry setWkbGeometry(ByteBuffer wkbGeometry) {
    this.wkbGeometry = wkbGeometry;
    return this;
  }

  public void unsetWkbGeometry() {
    this.wkbGeometry = null;
  }

  /** Returns true if field wkbGeometry is set (has been asigned a value) and false otherwise */
  public boolean isSetWkbGeometry() {
    return this.wkbGeometry != null;
  }

  public void setWkbGeometryIsSet(boolean value) {
    if (!value) {
      this.wkbGeometry = null;
    }
  }

  /**
   * 
   * @see com.foursquare.twofishes.YahooWoeType
   */
  public com.foursquare.twofishes.YahooWoeType getWoeType() {
    return this.woeType;
  }

  /**
   * 
   * @see com.foursquare.twofishes.YahooWoeType
   */
  public CellGeometry setWoeType(com.foursquare.twofishes.YahooWoeType woeType) {
    this.woeType = woeType;
    return this;
  }

  public void unsetWoeType() {
    this.woeType = null;
  }

  /** Returns true if field woeType is set (has been asigned a value) and false otherwise */
  public boolean isSetWoeType() {
    return this.woeType != null;
  }

  public void setWoeTypeIsSet(boolean value) {
    if (!value) {
      this.woeType = null;
    }
  }

  public boolean isFull() {
    return this.full;
  }

  public CellGeometry setFull(boolean full) {
    this.full = full;
    setFullIsSet(true);
    return this;
  }

  public void unsetFull() {
    __isset_bit_vector.clear(__FULL_ISSET_ID);
  }

  /** Returns true if field full is set (has been asigned a value) and false otherwise */
  public boolean isSetFull() {
    return __isset_bit_vector.get(__FULL_ISSET_ID);
  }

  public void setFullIsSet(boolean value) {
    __isset_bit_vector.set(__FULL_ISSET_ID, value);
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case OID:
      if (value == null) {
        unsetOid();
      } else {
        setOid((ByteBuffer)value);
      }
      break;

    case WKB_GEOMETRY:
      if (value == null) {
        unsetWkbGeometry();
      } else {
        setWkbGeometry((ByteBuffer)value);
      }
      break;

    case WOE_TYPE:
      if (value == null) {
        unsetWoeType();
      } else {
        setWoeType((com.foursquare.twofishes.YahooWoeType)value);
      }
      break;

    case FULL:
      if (value == null) {
        unsetFull();
      } else {
        setFull((Boolean)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case OID:
      return getOid();

    case WKB_GEOMETRY:
      return getWkbGeometry();

    case WOE_TYPE:
      return getWoeType();

    case FULL:
      return new Boolean(isFull());

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been asigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case OID:
      return isSetOid();
    case WKB_GEOMETRY:
      return isSetWkbGeometry();
    case WOE_TYPE:
      return isSetWoeType();
    case FULL:
      return isSetFull();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof CellGeometry)
      return this.equals((CellGeometry)that);
    return false;
  }

  public boolean equals(CellGeometry that) {
    if (that == null)
      return false;

    boolean this_present_oid = true && this.isSetOid();
    boolean that_present_oid = true && that.isSetOid();
    if (this_present_oid || that_present_oid) {
      if (!(this_present_oid && that_present_oid))
        return false;
      if (!this.oid.equals(that.oid))
        return false;
    }

    boolean this_present_wkbGeometry = true && this.isSetWkbGeometry();
    boolean that_present_wkbGeometry = true && that.isSetWkbGeometry();
    if (this_present_wkbGeometry || that_present_wkbGeometry) {
      if (!(this_present_wkbGeometry && that_present_wkbGeometry))
        return false;
      if (!this.wkbGeometry.equals(that.wkbGeometry))
        return false;
    }

    boolean this_present_woeType = true && this.isSetWoeType();
    boolean that_present_woeType = true && that.isSetWoeType();
    if (this_present_woeType || that_present_woeType) {
      if (!(this_present_woeType && that_present_woeType))
        return false;
      if (!this.woeType.equals(that.woeType))
        return false;
    }

    boolean this_present_full = true && this.isSetFull();
    boolean that_present_full = true && that.isSetFull();
    if (this_present_full || that_present_full) {
      if (!(this_present_full && that_present_full))
        return false;
      if (this.full != that.full)
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return 0;
  }

  public int compareTo(CellGeometry other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;
    CellGeometry typedOther = (CellGeometry)other;

    lastComparison = Boolean.valueOf(isSetOid()).compareTo(typedOther.isSetOid());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetOid()) {
      lastComparison = TBaseHelper.compareTo(this.oid, typedOther.oid);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetWkbGeometry()).compareTo(typedOther.isSetWkbGeometry());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetWkbGeometry()) {
      lastComparison = TBaseHelper.compareTo(this.wkbGeometry, typedOther.wkbGeometry);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetWoeType()).compareTo(typedOther.isSetWoeType());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetWoeType()) {
      lastComparison = TBaseHelper.compareTo(this.woeType, typedOther.woeType);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetFull()).compareTo(typedOther.isSetFull());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetFull()) {
      lastComparison = TBaseHelper.compareTo(this.full, typedOther.full);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(TProtocol iprot) throws TException {
    TField field;
    iprot.readStructBegin();
    while (true)
    {
      field = iprot.readFieldBegin();
      if (field.type == TType.STOP) { 
        break;
      }
      switch (field.id) {
        case 1: // OID
          if (field.type == TType.STRING) {
            this.oid = iprot.readBinary();
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 2: // WKB_GEOMETRY
          if (field.type == TType.STRING) {
            this.wkbGeometry = iprot.readBinary();
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 3: // WOE_TYPE
          if (field.type == TType.I32) {
            this.woeType = com.foursquare.twofishes.YahooWoeType.findByValue(iprot.readI32());
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 4: // FULL
          if (field.type == TType.BOOL) {
            this.full = iprot.readBool();
            setFullIsSet(true);
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        default:
          TProtocolUtil.skip(iprot, field.type);
      }
      iprot.readFieldEnd();
    }
    iprot.readStructEnd();

    // check for required fields of primitive type, which can't be checked in the validate method
    validate();
  }

  public void write(TProtocol oprot) throws TException {
    validate();

    oprot.writeStructBegin(STRUCT_DESC);
    if (this.oid != null) {
      if (isSetOid()) {
        oprot.writeFieldBegin(OID_FIELD_DESC);
        oprot.writeBinary(this.oid);
        oprot.writeFieldEnd();
      }
    }
    if (this.wkbGeometry != null) {
      if (isSetWkbGeometry()) {
        oprot.writeFieldBegin(WKB_GEOMETRY_FIELD_DESC);
        oprot.writeBinary(this.wkbGeometry);
        oprot.writeFieldEnd();
      }
    }
    if (this.woeType != null) {
      if (isSetWoeType()) {
        oprot.writeFieldBegin(WOE_TYPE_FIELD_DESC);
        oprot.writeI32(this.woeType.getValue());
        oprot.writeFieldEnd();
      }
    }
    if (isSetFull()) {
      oprot.writeFieldBegin(FULL_FIELD_DESC);
      oprot.writeBool(this.full);
      oprot.writeFieldEnd();
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("CellGeometry(");
    boolean first = true;

    if (isSetOid()) {
      sb.append("oid:");
      if (this.oid == null) {
        sb.append("null");
      } else {
        TBaseHelper.toString(this.oid, sb);
      }
      first = false;
    }
    if (isSetWkbGeometry()) {
      if (!first) sb.append(", ");
      sb.append("wkbGeometry:");
      if (this.wkbGeometry == null) {
        sb.append("null");
      } else {
        TBaseHelper.toString(this.wkbGeometry, sb);
      }
      first = false;
    }
    if (isSetWoeType()) {
      if (!first) sb.append(", ");
      sb.append("woeType:");
      if (this.woeType == null) {
        sb.append("null");
      } else {
        sb.append(this.woeType);
      }
      first = false;
    }
    if (isSetFull()) {
      if (!first) sb.append(", ");
      sb.append("full:");
      sb.append(this.full);
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws TException {
    // check for required fields
  }

}

