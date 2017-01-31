import React from 'react';
import { withRouter } from 'react-router';
import DateTime from 'react-datetime';

import AppSelect from 'components/AppSelectComponent';
import ClusterSelect from 'components/ClusterSelectComponent';
import ProcSelect from 'components/ProcSelectComponent';
import ProfileList from 'components/ProfileListComponent';
import TraceList from 'components/TraceListComponent';

import styles from './AppComponent.css';

const AppComponent = (props) => {
  const selectedApp = props.location.query.app;
  const selectedCluster = props.location.query.cluster;
  const selectedProc = props.location.query.proc;
  const startTime = props.location.query.startTime;
  const endTime = props.location.query.endTime;
  const workType = props.location.query.workType;

  const updateQueryParams = ({ pathname = '/', query }) => props.router.push({ pathname, query });
  const updateAppQueryParam = o => updateQueryParams({ query: { app: o.name } });
  const updateClusterQueryParam = (o) => {
    updateQueryParams({ query: { app: selectedApp, cluster: o.name } });
  };
  const updateProcQueryParam = (o) => {
    updateQueryParams({ query: { app: selectedApp, cluster: selectedCluster, proc: o.name } });
  };
  const updateStartTime = (dateTimeObject) => {
    updateQueryParams({
      query: {
        app: selectedApp,
        cluster: selectedCluster,
        proc: selectedProc,
        startTime: dateTimeObject.toISOString(),
      },
    });
  };
  const updateEndTime = (dateTimeObject) => {
    updateQueryParams({
      query: {
        app: selectedApp,
        cluster: selectedCluster,
        proc: selectedProc,
        startTime,
        endTime: dateTimeObject.toISOString(),
      },
    });
  };

  return (
    <div>
      <div className="mdl-grid">
        <div className="mdl-cell mdl-cell--3-col">
          <AppSelect
            onChange={updateAppQueryParam}
            value={selectedApp}
          />
        </div>
        <div className="mdl-cell mdl-cell--3-col">
          {selectedApp && (
            <ClusterSelect
              app={selectedApp}
              onChange={updateClusterQueryParam}
              value={selectedCluster}
            />
          )}
        </div>
        <div className="mdl-cell mdl-cell--2-col">
          {selectedApp && selectedCluster && (
            <ProcSelect
              app={selectedApp}
              cluster={selectedCluster}
              onChange={updateProcQueryParam}
              value={selectedProc}
            />
          )}
        </div>
        {
          selectedApp && selectedCluster && selectedProc && (
            <div className="mdl-cell mdl-cell--2-col">
              <div>
                <label className={styles['label']} htmlFor="startTime">Select Start time</label>
                <DateTime
                  className={styles['date-time']}
                  defaultValue={startTime ? new Date(startTime) : ''}
                  onBlur={updateStartTime}
                  dateFormat="DD-MM-YYYY"
                />
              </div>
            </div>
          )
        }
        {
          selectedApp && selectedCluster && selectedProc && (
            <div className="mdl-cell mdl-cell--2-col">
              <div>
                <label className={styles['label']} htmlFor="endTime">Select End time</label>
                <DateTime
                  className={styles['date-time']}
                  defaultValue={endTime ? new Date(endTime) : ''}
                  onBlur={updateEndTime}
                  dateFormat="DD-MM-YYYY"
                />
              </div>
            </div>
          )
        }
      </div>
      {
        selectedProc && startTime && endTime && (
          <div className="mdl-grid">
            <div className={`mdl-cell mdl-cell--${workType ? '8' : '12'}-col`}>
              <ProfileList
                app={selectedApp}
                cluster={selectedCluster}
                proc={selectedProc}
                startTime={startTime}
                endTime={endTime}
              />
            </div>
            {
              workType && (
                <div className="mdl-cell mdl-cell--4-col">
                  <TraceList
                    app={selectedApp}
                    cluster={selectedCluster}
                    proc={selectedProc}
                    workType={workType}
                    startTime={startTime}
                  />
                </div>
              )
            }
          </div>
        )}
    </div>
  );
};

AppComponent.propTypes = {
  location: React.PropTypes.object,
};

export default withRouter(AppComponent);
