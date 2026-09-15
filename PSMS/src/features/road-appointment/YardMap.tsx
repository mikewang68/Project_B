import { useEffect, useRef, useState } from 'react';
import 'maplibre-gl/dist/maplibre-gl.css';

import type { Appointment } from '../../contracts';
import { businessLabel } from '../../presentation/businessCopy';
import { appointmentLocation, displayVehicleNo } from './model';

type YardMapProps = Readonly<{
  appointments: readonly Appointment[];
  selectedId?: string;
  onSelect: (appointmentId: string) => void;
}>;

const vehicleCoordinates: readonly [number, number][] = [
  [104.0578, 30.6696],
  [104.0593, 30.6697],
  [104.0611, 30.6705],
  [104.0622, 30.6716],
  [104.0608, 30.6725],
  [104.0587, 30.6722],
];

function LocationFallback({ appointments, selectedId, onSelect }: YardMapProps) {
  return (
    <div className="yard-map-fallback" role="list" aria-label="车辆位置列表">
      {appointments.map((appointment, index) => (
        <button
          key={appointment.id}
          type="button"
          className={appointment.id === selectedId ? 'is-selected' : ''}
          onClick={() => onSelect(appointment.id)}
          role="listitem"
        >
          <span>{displayVehicleNo(appointment.vehicleNo)}</span>
          <small>{appointmentLocation(index)} · {businessLabel(appointment.status)}</small>
        </button>
      ))}
    </div>
  );
}

export default function YardMap(props: YardMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [fallback, setFallback] = useState(
    typeof window === 'undefined' || typeof window.WebGLRenderingContext === 'undefined',
  );

  useEffect(() => {
    if (fallback || !containerRef.current) return undefined;
    let disposed = false;
    let map: import('maplibre-gl').Map | undefined;
    const markers: import('maplibre-gl').Marker[] = [];

    void import('maplibre-gl')
      .then((maplibre) => {
        if (disposed || !containerRef.current) return;
        const vehicleData: GeoJSON.FeatureCollection = {
          type: 'FeatureCollection',
          features: props.appointments.map((appointment, index) => ({
            type: 'Feature',
            properties: {
              id: appointment.id,
              selected: appointment.id === props.selectedId ? 1 : 0,
              label: displayVehicleNo(appointment.vehicleNo),
            },
            geometry: { type: 'Point', coordinates: vehicleCoordinates[index % vehicleCoordinates.length] },
          })),
        };
        map = new maplibre.Map({
          container: containerRef.current,
          center: [104.0601, 30.6711],
          zoom: containerRef.current.clientWidth < 380 ? 14.8 : 15.4,
          attributionControl: false,
          style: {
            version: 8,
            sources: {
              yard: {
                type: 'geojson',
                data: {
                  type: 'FeatureCollection',
                  features: [
                    {
                      type: 'Feature',
                      properties: {},
                      geometry: {
                        type: 'Polygon',
                        coordinates: [[
                          [104.0568, 30.6690], [104.0635, 30.6690], [104.0635, 30.6731],
                          [104.0568, 30.6731], [104.0568, 30.6690],
                        ]],
                      },
                    },
                  ],
                },
              },
              route: {
                type: 'geojson',
                data: {
                  type: 'FeatureCollection',
                  features: [{
                    type: 'Feature', properties: {}, geometry: {
                      type: 'LineString',
                      coordinates: vehicleCoordinates,
                    },
                  }],
                },
              },
              vehicles: { type: 'geojson', data: vehicleData },
            },
            layers: [
              { id: '背景', type: 'background', paint: { 'background-color': '#F5F7FA' } },
              { id: '场区', type: 'fill', source: 'yard', paint: { 'fill-color': '#E4E7ED', 'fill-opacity': 0.9 } },
              { id: '场区边界', type: 'line', source: 'yard', paint: { 'line-color': '#909399', 'line-width': 2 } },
              { id: '车辆路线', type: 'line', source: 'route', paint: { 'line-color': '#409EFF', 'line-width': 4, 'line-dasharray': [2, 2] } },
              {
                id: '车辆', type: 'circle', source: 'vehicles',
                paint: {
                  'circle-color': ['case', ['==', ['get', 'selected'], 1], '#F56C6C', '#409EFF'],
                  'circle-radius': ['case', ['==', ['get', 'selected'], 1], 9, 7],
                  'circle-stroke-color': '#ffffff', 'circle-stroke-width': 2,
                },
              },
            ],
          },
        });
        props.appointments.forEach((appointment, index) => {
          const markerElement = document.createElement('button');
          markerElement.type = 'button';
          markerElement.className = appointment.id === props.selectedId
            ? 'yard-map-marker is-selected'
            : 'yard-map-marker';
          markerElement.setAttribute(
            'aria-label',
            `${displayVehicleNo(appointment.vehicleNo)}，${appointmentLocation(index)}，${businessLabel(appointment.status)}`,
          );

          const vehicleLabel = document.createElement('strong');
          vehicleLabel.textContent = displayVehicleNo(appointment.vehicleNo);
          const statusLabel = document.createElement('span');
          statusLabel.textContent = businessLabel(appointment.status);
          markerElement.append(vehicleLabel, statusLabel);
          markerElement.addEventListener('click', () => props.onSelect(appointment.id));

          markers.push(
            new maplibre.Marker({ element: markerElement, anchor: 'bottom' })
              .setLngLat(vehicleCoordinates[index % vehicleCoordinates.length])
              .addTo(map!),
          );
        });
        map.on('click', '车辆', (event) => {
          const appointmentId = event.features?.[0]?.properties?.id;
          if (typeof appointmentId === 'string') props.onSelect(appointmentId);
        });
        map.on('error', () => setFallback(true));
      })
      .catch(() => setFallback(true));

    return () => {
      disposed = true;
      markers.forEach((marker) => marker.remove());
      map?.remove();
    };
  }, [fallback, props.appointments, props.onSelect, props.selectedId]);

  if (fallback) return <LocationFallback {...props} />;
  return <div ref={containerRef} className="yard-map-canvas" aria-label="本地场区车辆地图" />;
}
