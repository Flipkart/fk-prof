import React, { Component } from 'react';
import { withRouter } from 'react-router';
import DateTime from 'react-datetime';

import Header from 'components/HeaderComponent';
import AppSelect from 'components/AppSelectComponent';
import ClusterSelect from 'components/ClusterSelectComponent';
import ProcSelect from 'components/ProcSelectComponent';
import TraceList from 'components/TraceListComponent';

import styles from 'components/RootComponent/RootComponent.css';

const BaseComponent = Komponent => class extends Component {
  componentDidUpdate () {
      componentHandler.upgradeDom(); // eslint-disable-line
  }

  render () {
    return <Komponent {...this.props} />;
  }
};

const RootComponent = (props) => {
  const selectedApp = props.location.query.app;
  const selectedCluster = props.location.query.cluster;
  const selectedProc = props.location.query.proc;
  const startTime = props.location.query.startTime;
  const selectedWorkType = props.location.query.workType;

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
  const updateWorkType = workType => updateQueryParams({
    query: { ...props.location.query, workType },
  });

  return (
    <div>
      <Header />
      <main style={{ paddingTop: 64 }}>
        <div className="page-content">
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
            <div className="mdl-cell mdl-cell--3-col">
              {selectedApp && selectedCluster && (
                <ProcSelect
                  app={selectedApp}
                  cluster={selectedCluster}
                  onChange={updateProcQueryParam}
                  value={selectedProc}
                />
              )}
            </div>
            <div className="mdl-cell mdl-cell--3-col">
              {
                selectedApp && selectedCluster && selectedProc && (
                  <div>
                    <label className={styles['label']} htmlFor="startTime">Select Start time</label>
                    <DateTime
                      className={styles['date-time']}
                      value={startTime ? new Date(startTime) : ''}
                      onBlur={updateStartTime}
                      dateFormat="DD-MM-YYYY"
                    />
                  </div>
                )
              }
            </div>
          </div>
          {
            selectedProc && startTime && (
              <div className="mdl-grid">
                <div className="mdl-cell mdl-cell--12-col">
                  { /* More workTypes to come here */ }
                  <button
                    className="mdl-button mdl-js-button mdl-button--raised mdl-button--accent"
                    onClick={updateWorkType.bind(null, 'cpuSampling')}
                  >
                    CPU Sampling
                  </button>
                </div>
              </div>
            )
          }

          {selectedWorkType && (
            <div className="mdl-grid">
              <div className="mdl-cell mdl-cell--12-col">
                <TraceList
                  app={selectedApp}
                  cluster={selectedCluster}
                  proc={selectedProc}
                  workType={selectedWorkType}
                  startTime={startTime}
                />
              </div>
            </div>
          )}

          { props.children }
        </div>
      </main>
    </div>
  );
};

RootComponent.propTypes = {
  children: React.PropTypes.node,
  location: React.PropTypes.object,
};

export default BaseComponent(withRouter(RootComponent));
