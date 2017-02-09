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
  const start = props.location.query.start;
  const end = props.location.query.end;
  const workType = props.location.query.workType;
  const profileStart = props.location.query.profileStart;

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
        start: dateTimeObject.toISOString(),
      },
    });
  };
  const updateEndTime = (dateTimeObject) => {
    updateQueryParams({
      query: {
        app: selectedApp,
        cluster: selectedCluster,
        proc: selectedProc,
        start,
        end: dateTimeObject.toISOString(),
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
                  defaultValue={start ? new Date(start) : ''}
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
                  defaultValue={end ? new Date(end) : ''}
                  onBlur={updateEndTime}
                  dateFormat="DD-MM-YYYY"
                />
              </div>
            </div>
          )
        }
      </div>
      {
        selectedProc && start && end && (
          <div className="mdl-grid">
            <div className={`mdl-cell mdl-cell--${workType ? '8' : '12'}-col`}>
              <ProfileList
                app={selectedApp}
                cluster={selectedCluster}
                proc={selectedProc}
                start={start}
                end={end}
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
                    start={start}
                    profileStart={profileStart}
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
