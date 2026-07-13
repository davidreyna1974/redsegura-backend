package com.redsegura.assetinventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * Ubicación física estructurada (DCIM): sitio, sala, fila, rack y unidad de rack. Embebida en
 * {@link Device} (no es un recurso propio en el MVP). Las columnas usan prefijo {@code loc_} para
 * evitar palabras reservadas de SQL (p. ej. {@code row}).
 */
@Embeddable
public class Location {

  @Column(name = "loc_site")
  private String site;

  @Column(name = "loc_room")
  private String room;

  @Column(name = "loc_row")
  private String row;

  @Column(name = "loc_rack")
  private String rack;

  @Column(name = "loc_rack_unit")
  private Integer rackUnit;

  protected Location() {}

  public Location(String site, String room, String row, String rack, Integer rackUnit) {
    this.site = site;
    this.room = room;
    this.row = row;
    this.rack = rack;
    this.rackUnit = rackUnit;
  }

  public String getSite() {
    return site;
  }

  public String getRoom() {
    return room;
  }

  public String getRow() {
    return row;
  }

  public String getRack() {
    return rack;
  }

  public Integer getRackUnit() {
    return rackUnit;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Location other)) {
      return false;
    }
    return Objects.equals(site, other.site)
        && Objects.equals(room, other.room)
        && Objects.equals(row, other.row)
        && Objects.equals(rack, other.rack)
        && Objects.equals(rackUnit, other.rackUnit);
  }

  @Override
  public int hashCode() {
    return Objects.hash(site, room, row, rack, rackUnit);
  }
}
