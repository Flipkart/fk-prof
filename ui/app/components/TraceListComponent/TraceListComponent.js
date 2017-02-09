import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';

import fetchTracesAction from 'actions/TraceActions';
import { getUniqueId } from 'reducers/TraceReducer';
import { objectToQueryParams } from 'utils/UrlUtils';
import safeTraverse from 'utils/safeTraverse';
import Loader from 'components/LoaderComponent';

import styles from './TraceListComponent.css';

class TraceListComponent extends Component {
  componentDidMount () {
    const { cluster, app, proc, profileStart, workType } = this.props;
    if (cluster && app && proc && profileStart && workType) {
      this.props.getTraces({ cluster, app, proc, query: { start: profileStart }, workType });
    }
  }

  componentWillReceiveProps (nextProps) {
    const didAppChange = nextProps.app !== this.props.app;
    const didClusterChange = nextProps.cluster !== this.props.cluster;
    const didProcChange = nextProps.proc !== this.props.proc;
    const didWorkTypeChange = nextProps.workType !== this.props.workType;
    const didStartTimeChange = nextProps.start !== this.props.start;
    const didProfileStartTimeChange = nextProps.profileStart !== this.props.profileStart;

    const didAnythingChange = didAppChange || didClusterChange ||
      didProcChange || didWorkTypeChange || didStartTimeChange ||
      didProfileStartTimeChange;

    if (didAnythingChange) {
      this.props.getTraces({
        app: nextProps.app,
        cluster: nextProps.cluster,
        workType: nextProps.workType,
        proc: nextProps.proc,
        query: { start: nextProps.profileStart },
      });
    }
  }

  render () {
    const { traces, workType, cluster, app, proc, profileStart } = this.props;
    const queryParams = { workType, cluster, app, proc, profileStart };
    if (!traces) return null;
    if (traces.asyncStatus === 'PENDING') {
      return (
        <Loader />
      );
    }

    if (traces.asyncStatus === 'SUCCESS') {
      return (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Trace Name</th>
            </tr>
          </thead>
          <tbody>
            {traces.data.map(t => (
              <tr key={t.name}>
                <td>
                  <a
                    rel="noopener noreferrer"
                    target="_blank"
                    href={`/work-type/${workType}/${t.name}?${objectToQueryParams(queryParams)}`}
                  >
                    {t.name}
                  </a>
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


const mapStateToProps = (state, ownProps) => ({
  traces: safeTraverse(state, ['traces', [getUniqueId(ownProps)]]),
});

const mapDispatchToProps = dispatch => ({
  getTraces: params => dispatch(fetchTracesAction(params)),
});

TraceListComponent.propTypes = {
  app: PropTypes.string.isRequired,
  cluster: PropTypes.string.isRequired,
  proc: PropTypes.string.isRequired,
  workType: PropTypes.string.isRequired,
  start: PropTypes.string.isRequired,
  profileStart: PropTypes.string.isRequired,
  traces: PropTypes.object,
  getTraces: PropTypes.func.isRequired,
};

export default connect(mapStateToProps, mapDispatchToProps)(TraceListComponent);
