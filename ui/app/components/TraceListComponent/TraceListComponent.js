import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';

import fetchTracesAction from 'actions/TraceActions';
import { getUniqueId } from 'reducers/TraceReducer';
import safeTraverse from 'utils/safeTraverse';

import styles from './TraceListComponent.css';

class TraceListComponent extends Component {
  componentDidMount () {
    const { cluster, app, proc, startTime = 'yo', workType } = this.props;
    if (cluster && app && proc && startTime && workType) {
      this.props.getTraces({ cluster, app, proc, startTime, workType });
    }
  }

  componentWillReceiveProps (nextProps) {
    const didAppChange = nextProps.app !== this.props.app;
    const didClusterChange = nextProps.cluster !== this.props.cluster;
    const didProcChange = nextProps.proc !== this.props.proc;
    const didWorkTypeChange = nextProps.workType !== this.props.workType;
    const didStartTimeChange = nextProps.startTime !== this.props.startTime;

    const didAnythingChange = didAppChange || didClusterChange ||
      didProcChange || didWorkTypeChange || didStartTimeChange;

    if (didAnythingChange) {
      this.props.getTraces({
        app: nextProps.app,
        cluster: nextProps.cluster,
        workType: nextProps.workType,
        proc: nextProps.proc,
        startTime: nextProps.startTime,
      });
    }
  }

  render () {
    const { traces } = this.props;
    if (!traces) return null;
    if (traces.asyncStatus === 'PENDING') {
      return (
        <div
          className="mdl-progress mdl-js-progress mdl-progress__indeterminate"
        />
      );
    }

    if (traces.asyncStatus === 'SUCCESS') {
      return (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Trace Name</th>
              <th>Samples</th>
            </tr>
          </thead>
          <tbody>
            {traces.data.traces.map(t => (
              <tr>
                <td>{t.name}</td>
                <td>{t.props && t.props.samples}</td>
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
  startTime: PropTypes.string.isRequired,
  traces: PropTypes.object.isRequired,
  getTraces: PropTypes.func.isRequired,
};

export default connect(mapStateToProps, mapDispatchToProps)(TraceListComponent);
