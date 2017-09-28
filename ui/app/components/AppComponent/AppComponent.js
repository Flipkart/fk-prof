import React from 'react';
import { withRouter } from 'react-router';
import DateTime from 'react-datetime';

import AppSelect from 'components/AppSelectComponent';
import ClusterSelect from 'components/ClusterSelectComponent';
import ProcSelect from 'components/ProcSelectComponent';
import ProfileList from 'components/ProfileListComponent';

import styles from './AppComponent.css';
import PolicyComponent from "../PolicyComponent/PolicyComponent";

const AppComponent = (props) => {
  const selectedApp = props.location.query.app;
  const selectedCluster = props.location.query.cluster;
  const selectedProc = props.location.query.proc;
  const start = props.location.query.start;
  const end = start ? (new Date(start).getTime() + (24 * 3600 * 1000)) : '';

  const updateProfilesQueryParams = ({ pathname = '/profiles', query }) => props.router.push({ pathname, query });
  const updatePoliciesQueryParams = ({pathname = '/policies', query}) => props.router.push({pathname, query});

  const updateProfilesAppQueryParam = o => updateProfilesQueryParams({ query: { app: o.name } });
  const updatePoliciesAppQueryParam = o => updatePoliciesQueryParams({query: {app: o.name}});

  const updateProfilesClusterQueryParam = (o) => {
    updateProfilesQueryParams({ query: { app: selectedApp, cluster: o.name } });
  };
  const updatePoliciesClusterQueryParam = (o) => {
    updatePoliciesQueryParams({query: {app: selectedApp, cluster: o.name}});
  };

  const updateProfilesProcQueryParam = (o) => {
    updateProfilesQueryParams({ query: { app: selectedApp, cluster: selectedCluster, proc: o.name } });
  };
  const updatePoliciesProcQueryParam = (o) => {
    updatePoliciesQueryParams({query: {app: selectedApp, cluster: selectedCluster, proc: o.name}});
  };

  const updateStartTime = (dateTimeObject) => {
    updateProfilesQueryParams({
      query: {
        app: selectedApp,
        cluster: selectedCluster,
        proc: selectedProc,
        start: dateTimeObject.toISOString(),
      },
    });
  };
  const isPoliciesPage = props.location.pathname.startsWith('/policies');
  const columnWidth = isPoliciesPage ? 4 : 3;
  return (
    <div>
      <div>
        <div className="mdl-grid">
          <div className={`mdl-cell mdl-cell--${columnWidth}-col`}>
            <AppSelect
              onChange={isPoliciesPage ? updatePoliciesAppQueryParam : updateProfilesAppQueryParam}
              value={selectedApp}
              isPoliciesPage={isPoliciesPage}
            />
          </div>
          <div className={`mdl-cell mdl-cell--${columnWidth}-col`}>
            {selectedApp && (
              <ClusterSelect
                app={selectedApp}
                onChange={isPoliciesPage ? updatePoliciesClusterQueryParam : updateProfilesClusterQueryParam}
                value={selectedCluster}
                isPoliciesPage={isPoliciesPage}
              />
            )}
          </div>
          <div className={`mdl-cell mdl-cell--${columnWidth}-col`}>
            {selectedApp && selectedCluster && (
              <ProcSelect
                app={selectedApp}
                cluster={selectedCluster}
                onChange={isPoliciesPage ? updatePoliciesProcQueryParam : updateProfilesProcQueryParam}
                value={selectedProc}
                isPoliciesPage={isPoliciesPage}
              />
            )}
          </div>
          {!isPoliciesPage && selectedApp && selectedCluster && selectedProc && (
            <div className="mdl-cell mdl-cell--3-col">
              <label className={styles['label']} htmlFor="startTime">Date</label>
              <div>
                <DateTime
                  className={styles['date-time']}
                  defaultValue={start ? new Date(start) : ''}
                  onChange={updateStartTime}
                  dateFormat="DD-MM-YYYY"
                  timeFormat={false}
                />
              </div>
            </div>
          )
          }
        </div>
        {!isPoliciesPage && selectedProc && start && end && (
          <div className="mdl-grid">
            <div className="mdl-cell mdl-cell--3-col">
              <ProfileList
                app={selectedApp}
                cluster={selectedCluster}
                proc={selectedProc}
                start={start}
                end={end}
              />
            </div>
            <div className="mdl-cell mdl-cell--9-col">
              { props.children || <h2 className={styles.ingrained}>Select a Trace</h2>}
            </div>
          </div>
        )}
        {isPoliciesPage && selectedProc && (
          <div className="mdl-grid">
            <div className="mdl-layout-spacer"/>
            <PolicyComponent
              app={selectedApp}
              cluster={selectedCluster}
              proc={selectedProc}
            />
            <div className="mdl-layout-spacer"/>
          </div>
        )}
      </div>

    </div>
  );
};

AppComponent.propTypes = {
  location: React.PropTypes.object,
  children: React.PropTypes.node,
};

export default withRouter(AppComponent);
