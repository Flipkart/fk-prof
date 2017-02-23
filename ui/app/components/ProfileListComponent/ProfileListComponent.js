import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';
import { Link } from 'react-router';
import moment from 'moment';
import TreeView from 'react-treeview';

import { fetchProfilesAction } from 'actions/ProfileActions';
import { objectToQueryParams } from 'utils/UrlUtils';
import fetchTracesAction from 'actions/TraceActions';
import Loader from 'components/LoaderComponent';
import safeTraverse from 'utils/safeTraverse';
import { getUniqueId } from 'reducers/TraceReducer';

import styles from './ProfileListComponent.css';
import 'react-treeview/react-treeview.css';



class ProfileListComponent extends Component {
  componentDidMount () {
    const { app, cluster, proc, start, end } = this.props;
    const startObject = moment(start);
    const endObject = moment(end);
    this.props.fetchProfiles({
      app,
      cluster,
      proc,
      query: {
        start,
        duration: endObject.diff(startObject, 'seconds'),
      },
    });
  }

  render () {
    const { profiles, app, cluster, proc, start, end } = this.props;
    if (!profiles) return null;
    if (profiles.asyncStatus === 'PENDING') {
      return (
        <Loader />
      );
    }

    if (profiles.asyncStatus === 'SUCCESS') {
      return (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Start Time</th>
              <th>End Time</th>
              <th>Worktypes</th>
            </tr>
          </thead>
          <tbody>
            {profiles.data.map(profile => (
              <tr key={`${profile.start}${profile.end}`}>
                <td>{moment(profile.start).format('Do MMM YYYY, h:mm:ss a')}</td>
                <td>{moment(profile.end).format('Do MMM YYYY, h:mm:ss a')}</td>
                <td>
                  {
                    profile.values.map((workType) => {
                      const payload = {
                        cluster,
                        app,
                        proc,
                        query: { start: profile.start },
                        workType,
                      };
                      const traces = this.props.traces[getUniqueId(payload)] || {};
                      const queryParams = { workType, cluster, app, proc, profileStart: profile.start };
                      const content = {
                        PENDING: <Loader />,
                        SUCCESS: traces && traces.data && traces.data.map(traceName => (
                          <TreeView
                            key={traceName.name}
                            defaultCollapsed
                            className={styles.hidden}
                            nodeLabel={
                              <a
                                rel="noopener noreferrer"
                                target="_blank"
                                href={`/work-type/${workType}/${traceName.name}?${objectToQueryParams(queryParams)}`}
                              >
                                {traceName.name}
                              </a>
                            }
                          />
                        )),
                      }[traces.asyncStatus];
                      return (
                        <TreeView
                          defaultCollapsed
                          nodeLabel={workType}
                          key={workType}
                          onClick={this.props.getTraces.bind(null, payload)}
                        >
                          { content }
                        </TreeView>
                      );
                    })
                  }
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      );
    }
    return null;
  }
}

ProfileListComponent.propTypes = {
  app: PropTypes.string.isRequired,
  cluster: PropTypes.string.isRequired,
  proc: PropTypes.string.isRequired,
  start: PropTypes.string.isRequired,
  end: PropTypes.string.isRequired,
  fetchProfiles: PropTypes.func.isRequired,
  profiles: PropTypes.object.isRequired,
  getTraces: PropTypes.func.isRequired,
  traces: PropTypes.object,
};

const mapStateToProps = state => ({
  profiles: state.profiles || {},
  traces: safeTraverse(state, ['traces']),
});

const mapDispatchToProps = dispatch => ({
  fetchProfiles: params => dispatch(fetchProfilesAction(params)),
  getTraces: params => dispatch(fetchTracesAction(params)),
});

export default connect(mapStateToProps, mapDispatchToProps)(ProfileListComponent);
